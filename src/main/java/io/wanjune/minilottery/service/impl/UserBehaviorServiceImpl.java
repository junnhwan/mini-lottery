package io.wanjune.minilottery.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wanjune.minilottery.common.BusinessException;
import io.wanjune.minilottery.mapper.DailySignInMapper;
import io.wanjune.minilottery.mapper.UserCreditAccountMapper;
import io.wanjune.minilottery.mapper.po.DailySignIn;
import io.wanjune.minilottery.mapper.po.UserCreditAccount;
import io.wanjune.minilottery.mq.producer.MQProducer;
import io.wanjune.minilottery.service.UserBehaviorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.Map;

/**
 * 用户行为服务实现 — 签到返利 + 积分兑换
 *
 * Phase 5 新增：借鉴 big-market 的 BehaviorRebateService + CreditAdjustService
 *
 * 核心设计：
 * 1. 签到/兑换操作在事务内完成（写 DB）
 * 2. 事务提交后发 MQ（afterCommit）→ RebateConsumer 异步增加抽奖机会
 * 3. 为什么事务后发 MQ？→ 如果事务回滚但消息已发出，Consumer 会错误地增加抽奖次数
 *
 * @author zjh
 * @since 2026/3/16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBehaviorServiceImpl implements UserBehaviorService {

    private final DailySignInMapper dailySignInMapper;
    private final UserCreditAccountMapper userCreditAccountMapper;
    private final MQProducer mqProducer;
    private final ObjectMapper objectMapper;

    /** 签到奖励积分 */
    private static final int SIGN_IN_CREDIT = 10;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void signIn(String userId, String activityId) {
        log.info("用户签到 userId={}, activityId={}", userId, activityId);

        // 1. 插入签到记录（UNIQUE 索引幂等：同一天同一活动只能签一次）
        //    面试点：为什么用 UNIQUE 索引而不是先查再插？
        //    → 先查再插在并发下有时间窗口（TOCTOU），UNIQUE 索引是 DB 级别的保证
        try {
            DailySignIn signIn = DailySignIn.builder()
                    .userId(userId)
                    .activityId(activityId)
                    .signDate(LocalDate.now())
                    .build();
            dailySignInMapper.insert(signIn);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(1007, "今日已签到，明天再来吧");
        }

        // 2. 积分账户 +10（ON DUPLICATE KEY UPDATE 自动创建或累加）
        userCreditAccountMapper.insertOrAddCredit(userId, SIGN_IN_CREDIT);
        log.info("签到积分已发放 userId={}, credit=+{}", userId, SIGN_IN_CREDIT);

        // 3. 事务提交后发 MQ → RebateConsumer → 增加 1 次抽奖机会
        //    面试点：为什么是 afterCommit 而不是在事务内发？
        //    → 事务内发 MQ，如果事务回滚，Consumer 已经收到消息并增加了次数，造成不一致
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    String message = objectMapper.writeValueAsString(Map.of(
                            "userId", userId,
                            "activityId", activityId,
                            "type", "SIGN_IN",
                            "rewardCount", 1
                    ));
                    mqProducer.sendRebateMessage(message);
                    log.info("签到返利 MQ 已发送 userId={}", userId);
                } catch (Exception e) {
                    log.error("签到返利 MQ 发送失败 userId={}", userId, e);
                }
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void creditExchange(String userId, String activityId, int cost) {
        log.info("积分兑换 userId={}, activityId={}, cost={}", userId, activityId, cost);

        // 1. 扣减积分（WHERE available_credit >= cost 防止扣成负数）
        //    返回影响行数：0 表示余额不足
        //    面试点：为什么不是先查余额再扣？
        //    → 和签到幂等一样的道理，先查再扣有并发窗口，UPDATE WHERE 是原子操作
        int rows = userCreditAccountMapper.deductCredit(userId, cost);
        if (rows == 0) {
            throw new BusinessException(1008, "积分不足");
        }
        log.info("积分扣减成功 userId={}, cost={}", userId, cost);

        // 2. 事务提交后发 MQ → RebateConsumer → 增加 1 次抽奖机会
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    String message = objectMapper.writeValueAsString(Map.of(
                            "userId", userId,
                            "activityId", activityId,
                            "type", "CREDIT_EXCHANGE",
                            "rewardCount", 1
                    ));
                    mqProducer.sendRebateMessage(message);
                    log.info("积分兑换返利 MQ 已发送 userId={}", userId);
                } catch (Exception e) {
                    log.error("积分兑换返利 MQ 发送失败 userId={}", userId, e);
                }
            }
        });
    }

    @Override
    public UserCreditAccount queryCredit(String userId) {
        return userCreditAccountMapper.queryByUserId(userId);
    }
}
