package io.wanjune.minilottery.service.algorithm.impl;

import io.wanjune.minilottery.mapper.po.Award;
import io.wanjune.minilottery.service.algorithm.IDrawAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;

/**
 * O(log n) 时间复杂度抽奖算法 — 有序区间表 + 二分查找
 *
 * 对应简历：「概率范围较大时自适应切换为有序区间表 + 二分查找，避免实时计算，提升接口响应速度」
 *
 * 核心思路（以 4 个奖品为例）：
 *   奖品概率: AirPods 0.1%, 优惠券 0.9%, 5元券 20%, 谢谢参与 79%
 *   最小概率 0.001 → rateRange = 1000（千分位精度）
 *
 *   装配阶段（armory）：
 *   1. 构建有序区间表：
 *      AirPods  → [1, 1]     （1000 × 0.001 = 1 个位置）
 *      优惠券   → [2, 10]    （1000 × 0.009 = 9 个位置）
 *      5元券    → [11, 210]  （1000 × 0.20 = 200 个位置）
 *      谢谢参与 → [211, 1000]（1000 × 0.79 = 790 个位置）
 *   2. 存入 Redis Hash，field 为 "from-to" 格式，value 为 awardId
 *
 *   抽奖阶段（dispatch）：
 *   1. random.nextInt(1000) + 1 → 比如得到 150
 *   2. 从 Redis 取出所有区间，用二分查找定位 150 落在哪个区间
 *   3. 150 在 [11, 210] 范围内 → 命中"5元券"
 *   4. 时间复杂度: O(log n)，n 为奖品数量
 *
 * 什么时候用这个算法？
 *   - 当 rateRange > 10000 时（概率精度极高，如 0.00001）
 *   - O(1) 方案需要 10 万+ 个 Redis Hash field，太浪费内存
 *   - O(log n) 只需要 n 个 field（n = 奖品数），内存开销极小
 *
 * 面试对比：
 *   - O(1)：查找极快，但内存开销随 rateRange 线性增长
 *   - O(log n)：查找稍慢（几微秒差距），但内存开销只和奖品数量有关
 *   - 选择策略：rateRange ≤ 10000 用 O(1)，否则用 O(log n)，自适应切换
 *
 * 参考 big-market: OLogNAlgorithm.java（big-market 还实现了多线程查找，这里简化为二分查找）
 *
 * @author zjh
 */
@Slf4j
@Component("oLogNAlgorithm")
@RequiredArgsConstructor
public class OLogNAlgorithm implements IDrawAlgorithm {

    private final RedisTemplate<String, Object> redisTemplate;

    private final SecureRandom secureRandom = new SecureRandom();

    private static final String TABLE_KEY_PREFIX = "strategy:table:";
    private static final String RANGE_KEY_PREFIX = "strategy:range:";

    /**
     * 装配 — 构建有序区间表
     *
     * 可以在这个方法打断点，观察：
     * - 每个奖品的 from/to 区间是如何计算的
     * - 最终存入 Redis Hash 的内容（"1-1":"R001", "2-10":"R002", ...）
     */
    @Override
    public void armory(String key, List<Award> awards, int rateRange) {
        log.info("O(log n) 算法装配开始 key={}, rateRange={}, 奖品数={}", key, rateRange, awards.size());

        // ========== 构建有序区间表 ==========
        // 累加 from/to，每个奖品占据一段连续区间
        int from = 1;
        Map<String, String> intervalTable = new LinkedHashMap<>();

        for (Award award : awards) {
            // 该奖品占据的槽位数
            int slotCount = award.getAwardRate().multiply(BigDecimal.valueOf(rateRange)).intValue();
            int to = from + slotCount - 1;

            // 存储区间: field = "from-to", value = awardId
            // 例: "1-1" → "R001", "2-10" → "R002"
            intervalTable.put(from + "-" + to, award.getAwardId());
            log.debug("区间分配: {} → [{}, {}]，占 {} 个槽位", award.getAwardId(), from, to, slotCount);

            from = to + 1;
        }

        String tableKey = TABLE_KEY_PREFIX + key;
        String rangeKey = RANGE_KEY_PREFIX + key;

        // 先删除旧数据
        redisTemplate.delete(tableKey);
        // 存入 Redis Hash
        redisTemplate.opsForHash().putAll(tableKey, intervalTable);
        // 存入 rateRange
        redisTemplate.opsForValue().set(rangeKey, rateRange);

        log.info("O(log n) 算法装配完成 key={}, 区间数={}", key, intervalTable.size());
    }

    /**
     * 抽奖 — 二分查找
     *
     * 可以在这个方法打断点，观察：
     * - intervals 列表的内容（排序后的区间）
     * - randomValue 的值
     * - 二分查找的过程（left, right, mid 的变化）
     */
    @Override
    public String dispatch(String key) {
        String rangeKey = RANGE_KEY_PREFIX + key;
        String tableKey = TABLE_KEY_PREFIX + key;

        // 1. 获取 rateRange
        Integer rateRange = (Integer) redisTemplate.opsForValue().get(rangeKey);
        if (rateRange == null) {
            throw new RuntimeException("策略未装配，请先调用 armory. key=" + key);
        }

        // 2. 从 Redis 取出所有区间
        Map<Object, Object> rawTable = redisTemplate.opsForHash().entries(tableKey);

        // 3. 解析区间并排序（按 from 升序）
        List<int[]> intervals = new ArrayList<>();      // [from, to, index]
        List<String> awardIds = new ArrayList<>();       // 对应的 awardId
        for (Map.Entry<Object, Object> entry : rawTable.entrySet()) {
            String field = entry.getKey().toString();    // "1-1", "2-10" 等
            String awardId = entry.getValue().toString();
            String[] parts = field.split("-");
            intervals.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
            awardIds.add(awardId);
        }
        // 按 from 排序（二分查找要求有序）
        Integer[] sortedIndices = new Integer[intervals.size()];
        for (int i = 0; i < sortedIndices.length; i++) sortedIndices[i] = i;
        Arrays.sort(sortedIndices, Comparator.comparingInt(i -> intervals.get(i)[0]));

        // 4. 生成随机数 [1, rateRange]
        int randomValue = secureRandom.nextInt(rateRange) + 1;

        // 5. 二分查找：找到 randomValue 落在哪个区间
        int left = 0, right = sortedIndices.length - 1;
        while (left <= right) {
            int mid = left + (right - left) / 2;
            int idx = sortedIndices[mid];
            int from = intervals.get(idx)[0];
            int to = intervals.get(idx)[1];

            if (randomValue < from) {
                right = mid - 1;
            } else if (randomValue > to) {
                left = mid + 1;
            } else {
                // 命中区间 [from, to]
                String awardId = awardIds.get(idx);
                log.debug("O(log n) 抽奖 key={}, randomValue={}, 命中区间[{},{}], awardId={}",
                        key, randomValue, from, to, awardId);
                return awardId;
            }
        }

        // 兜底：不应该走到这里（说明区间覆盖不完整）
        log.warn("O(log n) 二分查找未命中 key={}, randomValue={}", key, randomValue);
        return awardIds.get(awardIds.size() - 1);
    }
}
