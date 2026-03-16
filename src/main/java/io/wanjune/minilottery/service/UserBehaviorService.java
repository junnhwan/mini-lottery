package io.wanjune.minilottery.service;

import io.wanjune.minilottery.mapper.po.UserCreditAccount;

/**
 * 用户行为服务 — 签到返利 + 积分兑换
 *
 * Phase 5 新增：借鉴 big-market 的 BehaviorRebateService
 * 签到/兑换操作完成后，通过 MQ 异步增加抽奖机会
 *
 * @author zjh
 * @since 2026/3/16
 */
public interface UserBehaviorService {

    /**
     * 每日签到
     *
     * 业务流程：
     * 1. 插入 daily_sign_in 记录（UNIQUE 索引幂等，重复签到抛异常）
     * 2. 积分账户 +10
     * 3. 发 MQ 消息 → RebateConsumer → 增加 1 次抽奖机会
     *
     * @param userId     用户ID
     * @param activityId 活动ID
     */
    void signIn(String userId, String activityId);

    /**
     * 积分兑换抽奖机会
     *
     * 业务流程：
     * 1. 校验积分余额 >= cost
     * 2. 扣减积分
     * 3. 发 MQ 消息 → RebateConsumer → 增加 1 次抽奖机会
     *
     * @param userId     用户ID
     * @param activityId 活动ID
     * @param cost       消耗积分数
     */
    void creditExchange(String userId, String activityId, int cost);

    /**
     * 查询积分账户
     *
     * @param userId 用户ID
     * @return 积分账户信息，不存在则返回 null
     */
    UserCreditAccount queryCredit(String userId);
}
