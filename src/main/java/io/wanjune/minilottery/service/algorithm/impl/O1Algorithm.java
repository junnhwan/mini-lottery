package io.wanjune.minilottery.service.algorithm.impl;

import io.wanjune.minilottery.mapper.po.Award;
import io.wanjune.minilottery.service.algorithm.IDrawAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.*;

/**
 * O(1) 时间复杂度抽奖算法
 *
 * 对应简历：「通过空间换时间策略将概率装配至 Redis 哈希表，实现 O(1) 复杂度抽奖」
 *
 * 核心思路（以 4 个奖品为例）：
 *   奖品概率: AirPods 1%, 优惠券 9%, 5元券 20%, 谢谢参与 70%
 *   最小概率 0.01 → rateRange = 100（百分位精度）
 *
 *   装配阶段（armory）：
 *   1. 构建一个 size=100 的 ArrayList
 *   2. AirPods 占 100*0.01=1 个槽位，优惠券占 9 个，5元券占 20 个，谢谢参与占 70 个
 *   3. 列表内容: [R001, R002,R002,..., R003,R003,..., R004,R004,...]
 *   4. Collections.shuffle() 打乱顺序（防止概率分布有规律）
 *   5. 转为 HashMap<index, awardId> 存入 Redis Hash
 *
 *   抽奖阶段（dispatch）：
 *   1. random.nextInt(100) → 比如得到 42
 *   2. 从 Redis Hash 中 HGET key 42 → 返回 awardId
 *   3. 时间复杂度: O(1)，一次 Redis 调用
 *
 * 为什么叫"空间换时间"？
 *   - 用了 100 个 Redis Hash field 的存储空间（空间）
 *   - 换来了 O(1) 的查找速度（时间）
 *   - 对比原来的 O(n) 遍历匹配，空间多了但时间从线性变成了常数
 *
 * 适用条件: rateRange ≤ 10000（超过 10000 个槽位会浪费内存，切换到 OLogN 算法）
 *
 * 参考 big-market: O1Algorithm.java
 *
 * @author zjh
 */
@Slf4j
@Component("o1Algorithm")
@RequiredArgsConstructor
public class O1Algorithm implements IDrawAlgorithm {

    private final RedisTemplate<String, Object> redisTemplate;

    /** SecureRandom 比 ThreadLocalRandom 更适合抽奖场景（密码学级别随机性） */
    private final SecureRandom secureRandom = new SecureRandom();

    /** Redis key 前缀 */
    private static final String TABLE_KEY_PREFIX = "strategy:table:";
    private static final String RANGE_KEY_PREFIX = "strategy:range:";

    /**
     * 装配 — 构建 O(1) 概率查找表
     *
     * 可以在这个方法打断点，观察：
     * - strategyAwardSearchRateTables 列表的内容（展开前的概率分布）
     * - shuffle 后的列表（打乱后的分布）
     * - 最终存入 Redis Hash 的 map（index → awardId）
     */
    @Override
    public void armory(String key, List<Award> awards, int rateRange) {
        log.info("O(1) 算法装配开始 key={}, rateRange={}, 奖品数={}", key, rateRange, awards.size());

        // ========== 第 1 步：构建概率查找表 ==========
        // 原理：每个奖品按概率比例"占坑"
        // 例：rateRange=100, awardRate=0.09 → 占 100*0.09=9 个坑位
        List<String> strategyAwardSearchRateTables = new ArrayList<>(rateRange);
        for (Award award : awards) {
            String awardId = award.getAwardId();
            // 计算该奖品应占的槽位数 = rateRange × awardRate
            int slotCount = award.getAwardRate().multiply(java.math.BigDecimal.valueOf(rateRange)).intValue();
            for (int i = 0; i < slotCount; i++) {
                strategyAwardSearchRateTables.add(awardId);
            }
        }
        log.info("O(1) 查找表构建完成，总槽位数={}", strategyAwardSearchRateTables.size());

        // ========== 第 2 步：打乱顺序（shuffle） ==========
        // 为什么要 shuffle？
        // 如果不打乱，连续的 70 个槽位都是"谢谢参与"，虽然概率正确，但分布不均匀
        // shuffle 后，同一概率的奖品分散到不同位置，更"随机"
        Collections.shuffle(strategyAwardSearchRateTables);

        // ========== 第 3 步：转为 HashMap 并存入 Redis ==========
        // Redis Hash 结构: strategy:table:{key} → { "0":"R001", "1":"R004", "2":"R003", ... }
        Map<String, String> shuffledTable = new LinkedHashMap<>();
        for (int i = 0; i < strategyAwardSearchRateTables.size(); i++) {
            shuffledTable.put(String.valueOf(i), strategyAwardSearchRateTables.get(i));
        }

        String tableKey = TABLE_KEY_PREFIX + key;
        String rangeKey = RANGE_KEY_PREFIX + key;

        // 先删除旧数据（防止重复装配时数据残留）
        redisTemplate.delete(tableKey);
        // 存入 Redis Hash（一次 HSET 写入所有 field-value）
        redisTemplate.opsForHash().putAll(tableKey, shuffledTable);
        // 存入 rateRange（抽奖时需要知道随机数上界）
        redisTemplate.opsForValue().set(rangeKey, rateRange);

        log.info("O(1) 算法装配完成 key={}, Redis Hash fields={}", key, shuffledTable.size());
    }

    /**
     * 抽奖 — O(1) 查找
     *
     * 可以在这个方法打断点，观察：
     * - rateRange 的值（随机数上界）
     * - randomIndex 的值（生成的随机索引）
     * - HGET 返回的 awardId（命中的奖品）
     */
    @Override
    public String dispatch(String key) {
        String rangeKey = RANGE_KEY_PREFIX + key;
        String tableKey = TABLE_KEY_PREFIX + key;

        // 1. 获取 rateRange（概率范围，即随机数上界）
        Integer rateRange = (Integer) redisTemplate.opsForValue().get(rangeKey);
        if (rateRange == null) {
            throw new RuntimeException("策略未装配，请先调用 armory. key=" + key);
        }

        // 2. 生成 [0, rateRange) 范围内的随机数
        int randomIndex = secureRandom.nextInt(rateRange);

        // 3. 从 Redis Hash 中 O(1) 查找 → HGET strategy:table:{key} {randomIndex}
        Object awardId = redisTemplate.opsForHash().get(tableKey, String.valueOf(randomIndex));

        log.debug("O(1) 抽奖 key={}, randomIndex={}, awardId={}", key, randomIndex, awardId);
        return awardId != null ? awardId.toString() : null;
    }
}
