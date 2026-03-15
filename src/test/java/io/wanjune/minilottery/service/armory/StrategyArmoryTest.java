package io.wanjune.minilottery.service.armory;

import io.wanjune.minilottery.mapper.po.Award;
import io.wanjune.minilottery.service.algorithm.IDrawAlgorithm;
import io.wanjune.minilottery.service.algorithm.impl.O1Algorithm;
import io.wanjune.minilottery.service.algorithm.impl.OLogNAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 测试：O(1) / O(log n) 抽奖算法 + 策略装配
 *
 * 测试目标：
 * 1. 验证 O(1) 算法装配后 Redis 中的数据结构正确
 * 2. 验证 O(1) 算法抽奖概率分布符合预期
 * 3. 验证 O(log n) 算法装配和抽奖正确性
 * 4. 验证 StrategyArmory 自适应选择算法
 * 5. 验证实际活动数据的装配和抽奖
 *
 * 调试建议：
 * - 在 O1Algorithm.armory() 打断点，观察 shuffle 前后的列表变化
 * - 在 O1Algorithm.dispatch() 打断点，观察 randomIndex 和 HGET 结果
 * - 在 StrategyArmory.armory() 打断点，观察 rateRange 计算和算法选择
 *
 * 运行方式：需要本地 Redis 和 MySQL 服务
 */
@Slf4j
@SpringBootTest
class StrategyArmoryTest {

    @Autowired
    private StrategyArmory strategyArmory;

    @Autowired
    @Qualifier("o1Algorithm")
    private IDrawAlgorithm o1Algorithm;

    @Autowired
    @Qualifier("oLogNAlgorithm")
    private IDrawAlgorithm oLogNAlgorithm;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ==================== O(1) 算法测试 ====================

    @Test
    @DisplayName("O(1) 算法：装配后 Redis 数据结构验证")
    void test_o1_armory_redis_structure() {
        // 构造测试奖品：4 个奖品，概率之和 = 1.0
        List<Award> awards = buildTestAwards();
        String key = "test_o1_structure";
        int rateRange = 100;

        // 装配
        o1Algorithm.armory(key, awards, rateRange);

        // 验证 Redis 中的 rateRange
        Integer storedRange = (Integer) redisTemplate.opsForValue().get("strategy:range:" + key);
        assertEquals(rateRange, storedRange, "rateRange 应该存入 Redis");

        // 验证 Redis Hash 的 field 数量 = rateRange
        Long hashSize = redisTemplate.opsForHash().size("strategy:table:" + key);
        assertEquals(rateRange, hashSize, "Redis Hash 的 field 数量应等于 rateRange");

        // 验证每个 field 的 value 都是合法的 awardId
        Set<String> validAwardIds = Set.of("T001", "T002", "T003", "T004");
        Map<Object, Object> table = redisTemplate.opsForHash().entries("strategy:table:" + key);
        for (Map.Entry<Object, Object> entry : table.entrySet()) {
            assertTrue(validAwardIds.contains(entry.getValue().toString()),
                    "Redis Hash value 应该是合法的 awardId，实际: " + entry.getValue());
        }

        log.info("O(1) Redis 数据结构验证通过 ✓");

        // 清理
        redisTemplate.delete("strategy:table:" + key);
        redisTemplate.delete("strategy:range:" + key);
    }

    @Test
    @DisplayName("O(1) 算法：2000 次抽奖概率分布验证")
    void test_o1_probability_distribution() {
        List<Award> awards = buildTestAwards();
        String key = "test_o1_probability";
        int rateRange = 100;

        // 装配
        o1Algorithm.armory(key, awards, rateRange);

        // 抽奖 2000 次，统计各奖品命中次数
        // 注意：Redis 在云服务器上，每次 dispatch 需要 2 次远程调用（GET + HGET）
        // 10000 次 = 20000 次远程调用，网络延迟会导致测试超时
        // 2000 次在统计上足够验证概率分布
        Map<String, Integer> hitCount = new HashMap<>();
        int totalDraws = 2000;
        for (int i = 0; i < totalDraws; i++) {
            String awardId = o1Algorithm.dispatch(key);
            hitCount.merge(awardId, 1, Integer::sum);
        }

        // 打印实际概率分布
        log.info("===== O(1) 概率分布（{} 次抽奖）=====", totalDraws);
        for (Award award : awards) {
            int hits = hitCount.getOrDefault(award.getAwardId(), 0);
            double actualRate = (double) hits / totalDraws;
            double expectedRate = award.getAwardRate().doubleValue();
            log.info("{}: 期望 {}, 实际 {} ({} 次)",
                    award.getAwardId(), expectedRate, String.format("%.4f", actualRate), hits);
        }

        // 验证概率偏差在合理范围内（±5%）
        // 样本量 2000 次，适当放宽容差（统计波动比 10000 次大）
        assertProbability(hitCount, "T004", totalDraws, 0.70, 0.05);
        assertProbability(hitCount, "T003", totalDraws, 0.20, 0.05);
        assertProbability(hitCount, "T002", totalDraws, 0.09, 0.05);
        // T001 概率太低（1%），允许更大偏差
        assertProbability(hitCount, "T001", totalDraws, 0.01, 0.03);

        log.info("O(1) 概率分布验证通过 ✓");

        // 清理
        redisTemplate.delete("strategy:table:" + key);
        redisTemplate.delete("strategy:range:" + key);
    }

    // ==================== O(log n) 算法测试 ====================

    @Test
    @DisplayName("O(log n) 算法：装配 + 二分查找抽奖验证")
    void test_oLogN_armory_and_dispatch() {
        List<Award> awards = buildTestAwards();
        String key = "test_ologn";
        int rateRange = 100;

        // 装配
        oLogNAlgorithm.armory(key, awards, rateRange);

        // 验证 Redis 中的 rateRange
        Integer storedRange = (Integer) redisTemplate.opsForValue().get("strategy:range:" + key);
        assertEquals(rateRange, storedRange);

        // 抽奖 2000 次，验证概率分布
        Map<String, Integer> hitCount = new HashMap<>();
        int totalDraws = 2000;
        for (int i = 0; i < totalDraws; i++) {
            String awardId = oLogNAlgorithm.dispatch(key);
            assertNotNull(awardId, "O(log n) 抽奖结果不应为 null");
            hitCount.merge(awardId, 1, Integer::sum);
        }

        log.info("===== O(log n) 概率分布（{} 次抽奖）=====", totalDraws);
        for (Award award : awards) {
            int hits = hitCount.getOrDefault(award.getAwardId(), 0);
            double actualRate = (double) hits / totalDraws;
            log.info("{}: 期望 {}, 实际 {} ({} 次)",
                    award.getAwardId(), award.getAwardRate(), String.format("%.4f", actualRate), hits);
        }

        assertProbability(hitCount, "T004", totalDraws, 0.70, 0.05);
        assertProbability(hitCount, "T003", totalDraws, 0.20, 0.05);

        log.info("O(log n) 概率分布验证通过 ✓");

        // 清理
        redisTemplate.delete("strategy:table:" + key);
        redisTemplate.delete("strategy:range:" + key);
    }

    // ==================== StrategyArmory 集成测试 ====================

    @Test
    @DisplayName("StrategyArmory：自适应算法选择验证")
    void test_armory_algorithm_selection() {
        // 活动 A20260310001 的最小概率是 0.01 → rateRange=100 → 应选 O(1)
        strategyArmory.armory("A20260310001");

        Object algorithmName = redisTemplate.opsForValue().get("strategy:algorithm:A20260310001");
        assertNotNull(algorithmName, "算法类型应存入 Redis");
        assertEquals("o1Algorithm", algorithmName.toString(),
                "rateRange=100 ≤ 10000，应选择 O(1) 算法");

        log.info("自适应算法选择验证通过 ✓ 选中: {}", algorithmName);
    }

    @Test
    @DisplayName("StrategyArmory：使用真实活动数据抽奖")
    void test_armory_draw_with_real_data() {
        // 装配活动 A20260310001
        strategyArmory.armory("A20260310001");

        // 抽奖 500 次（真实数据测试，验证链路通畅即可）
        Map<String, Integer> hitCount = new HashMap<>();
        Set<String> validAwardIds = Set.of("R001", "R002", "R003", "R004");
        int totalDraws = 500;

        for (int i = 0; i < totalDraws; i++) {
            String awardId = strategyArmory.draw("A20260310001");
            assertNotNull(awardId, "抽奖结果不应为 null");
            assertTrue(validAwardIds.contains(awardId),
                    "抽奖结果应是该活动的合法奖品ID，实际: " + awardId);
            hitCount.merge(awardId, 1, Integer::sum);
        }

        log.info("===== 真实活动数据抽奖（{} 次）=====", totalDraws);
        log.info("R001(AirPods 1%): {} 次", hitCount.getOrDefault("R001", 0));
        log.info("R002(优惠券 9%): {} 次", hitCount.getOrDefault("R002", 0));
        log.info("R003(5元券 20%): {} 次", hitCount.getOrDefault("R003", 0));
        log.info("R004(谢谢参与 70%): {} 次", hitCount.getOrDefault("R004", 0));

        // 谢谢参与应该是最多的
        assertTrue(hitCount.getOrDefault("R004", 0) > hitCount.getOrDefault("R001", 0),
                "谢谢参与(70%)的命中次数应远大于 AirPods(1%)");

        log.info("真实活动数据抽奖验证通过 ✓");
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造测试奖品数据
     * T001: 1%, T002: 9%, T003: 20%, T004: 70%
     */
    private List<Award> buildTestAwards() {
        List<Award> awards = new ArrayList<>();

        Award a1 = new Award();
        a1.setAwardId("T001");
        a1.setAwardName("一等奖");
        a1.setAwardRate(new BigDecimal("0.01"));
        awards.add(a1);

        Award a2 = new Award();
        a2.setAwardId("T002");
        a2.setAwardName("二等奖");
        a2.setAwardRate(new BigDecimal("0.09"));
        awards.add(a2);

        Award a3 = new Award();
        a3.setAwardId("T003");
        a3.setAwardName("三等奖");
        a3.setAwardRate(new BigDecimal("0.20"));
        awards.add(a3);

        Award a4 = new Award();
        a4.setAwardId("T004");
        a4.setAwardName("谢谢参与");
        a4.setAwardRate(new BigDecimal("0.70"));
        awards.add(a4);

        return awards;
    }

    /**
     * 断言概率在合理范围内
     */
    private void assertProbability(Map<String, Integer> hitCount, String awardId,
                                   int totalDraws, double expectedRate, double tolerance) {
        int hits = hitCount.getOrDefault(awardId, 0);
        double actualRate = (double) hits / totalDraws;
        assertTrue(Math.abs(actualRate - expectedRate) < tolerance,
                String.format("%s 概率偏差过大: 期望 %.4f, 实际 %.4f (容差 %.4f)",
                        awardId, expectedRate, actualRate, tolerance));
    }
}
