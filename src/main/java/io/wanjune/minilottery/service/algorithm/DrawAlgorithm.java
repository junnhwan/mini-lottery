package io.wanjune.minilottery.service.algorithm;

import io.wanjune.minilottery.mapper.po.Award;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 抽奖算法
 *
 * @author zjh
 * @since 2026/3/10 19:25
 */
@Component
public class DrawAlgorithm {

    /**
     * 根据奖品概率列表，随机抽出一个奖品
     *
     * 思路：生成 [0, 1) 随机数，按 awardRate 累加，随机数落在哪个区间就中哪个奖
     * 例：AirPods 0.01, 优惠券 0.09, 谢谢参与 0.70
     *     区间：[0, 0.01) → AirPods, [0.01, 0.10) → 优惠券, ...
     *
     * @param awards 该活动下的所有奖品（含概率）
     * @return 命中的奖品
     */
    public Award draw(List<Award> awards) {
        double random = Math.random();
        double cumulative = 0;
        for (Award award : awards) {
            cumulative += award.getAwardRate().doubleValue();
            if (random < cumulative) {
                return award;
            }
        }
        // 兜底：浮点精度问题，返回最后一个奖品
        return awards.get(awards.size() - 1);
    }
}