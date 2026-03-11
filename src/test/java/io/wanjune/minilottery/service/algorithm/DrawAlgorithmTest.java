package io.wanjune.minilottery.service.algorithm;

import io.wanjune.minilottery.mapper.po.Award;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 抽奖算法单元测试
 *
 * 纯逻辑测试，不依赖 Spring 容器，执行速度快
 *
 * @author zjh
 * @since 2026/3/11
 */
class DrawAlgorithmTest {

    private DrawAlgorithm drawAlgorithm;
    private List<Award> awards;

    @BeforeEach
    void setUp() {
        drawAlgorithm = new DrawAlgorithm();

        // 构造测试奖品：AirPods 1%, 优惠券 9%, 谢谢参与 90%
        Award a1 = new Award();
        a1.setAwardId("AWARD_001");
        a1.setAwardName("AirPods");
        a1.setAwardRate(new BigDecimal("0.01"));

        Award a2 = new Award();
        a2.setAwardId("AWARD_002");
        a2.setAwardName("优惠券");
        a2.setAwardRate(new BigDecimal("0.09"));

        Award a3 = new Award();
        a3.setAwardId("AWARD_003");
        a3.setAwardName("谢谢参与");
        a3.setAwardRate(new BigDecimal("0.90"));

        awards = List.of(a1, a2, a3);
    }

    /**
     * 基本功能：抽奖结果必须是奖品列表中的一个
     */
    @Test
    void draw_shouldReturnAwardFromList() {
        Award result = drawAlgorithm.draw(awards);
        assertNotNull(result);
        assertTrue(awards.contains(result));
    }

    /**
     * 概率分布验证：抽 10000 次，统计各奖品命中比例是否大致符合配置
     *
     * AirPods 1% → 预期 ~100 次（允许 0-300）
     * 优惠券 9% → 预期 ~900 次（允许 500-1300）
     * 谢谢参与 90% → 预期 ~9000 次（允许 8500-9500）
     */
    @Test
    void draw_probabilityShouldMatchConfig() {
        int total = 10000;
        Map<String, Integer> counter = new HashMap<>();

        for (int i = 0; i < total; i++) {
            Award result = drawAlgorithm.draw(awards);
            counter.merge(result.getAwardId(), 1, Integer::sum);
        }

        int airpods = counter.getOrDefault("AWARD_001", 0);
        int coupon = counter.getOrDefault("AWARD_002", 0);
        int thanks = counter.getOrDefault("AWARD_003", 0);

        // 宽松断言：允许合理的统计波动
        assertTrue(airpods < 300, "AirPods 1% 但命中了 " + airpods + " 次，概率异常偏高");
        assertTrue(coupon > 500 && coupon < 1300, "优惠券 9% 但命中了 " + coupon + " 次，概率异常");
        assertTrue(thanks > 8500, "谢谢参与 90% 但只命中了 " + thanks + " 次，概率异常偏低");

        assertEquals(total, airpods + coupon + thanks, "总数应等于抽奖次数");
    }

    /**
     * 边界情况：只有一个奖品时，必定命中
     */
    @Test
    void draw_singleAward_shouldAlwaysReturnIt() {
        Award only = new Award();
        only.setAwardId("ONLY");
        only.setAwardName("唯一奖品");
        only.setAwardRate(new BigDecimal("1.00"));

        for (int i = 0; i < 100; i++) {
            assertEquals("ONLY", drawAlgorithm.draw(List.of(only)).getAwardId());
        }
    }
}
