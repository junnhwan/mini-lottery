package io.wanjune.minilottery.service.algorithm;

import io.wanjune.minilottery.mapper.po.Award;

import java.util.List;

/**
 * 抽奖算法接口
 *
 * 对应简历：「通过空间换时间策略将概率装配至 Redis 哈希表，实现 O(1) 复杂度抽奖；
 *           概率范围较大时自适应切换为有序区间表 + 二分查找」
 *
 * 两个核心方法分别对应两个阶段：
 * - armory（装配阶段）：应用启动时，将概率"展开"成查找表存入 Redis
 * - dispatch（抽奖阶段）：用户请求时，从 Redis 中 O(1) 或 O(log n) 查找奖品
 *
 * 两个实现类：
 * - O1Algorithm：哈希表方案，rateRange ≤ 10000 时使用
 * - OLogNAlgorithm：有序区间 + 二分查找方案，rateRange > 10000 时使用
 *
 * 参考 big-market: IAlgorithm.java
 *
 * @author zjh
 */
public interface IDrawAlgorithm {

    /**
     * 装配阶段 — 构建概率查找表，存入 Redis
     *
     * @param key       Redis 存储的 key 标识（通常为 activityId，权重规则下为 activityId_weightValue）
     * @param awards    该活动下的所有奖品（含概率 awardRate）
     * @param rateRange 概率范围值（如最小概率 0.01 → rateRange=100，表示百分位精度）
     */
    void armory(String key, List<Award> awards, int rateRange);

    /**
     * 抽奖阶段 — 从 Redis 查找表中随机获取一个奖品ID
     *
     * @param key Redis 存储的 key 标识
     * @return 命中的奖品ID（如 "R001"）
     */
    String dispatch(String key);
}
