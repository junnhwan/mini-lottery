package io.wanjune.minilottery.service.impl;

import io.wanjune.minilottery.cache.MultiLevelCacheService;
import io.wanjune.minilottery.common.BusinessException;
import io.wanjune.minilottery.common.enums.ActivityStatus;
import io.wanjune.minilottery.common.enums.OrderStatus;
import io.wanjune.minilottery.common.enums.TaskStatus;
import io.wanjune.minilottery.lock.StockService;
import io.wanjune.minilottery.mapper.ActivityMapper;
import io.wanjune.minilottery.mapper.AwardMapper;
import io.wanjune.minilottery.mapper.AwardTaskMapper;
import io.wanjune.minilottery.mapper.LotteryOrderMapper;
import io.wanjune.minilottery.mapper.UserParticipateCountMapper;
import io.wanjune.minilottery.mapper.po.Activity;
import io.wanjune.minilottery.mapper.po.Award;
import io.wanjune.minilottery.mapper.po.AwardTask;
import io.wanjune.minilottery.mapper.po.LotteryOrder;
import io.wanjune.minilottery.mapper.po.UserParticipateCount;
import io.wanjune.minilottery.mq.producer.MQProducer;
import io.wanjune.minilottery.service.LotteryService;
import io.wanjune.minilottery.service.armory.StrategyArmory;
import io.wanjune.minilottery.service.rule.chain.ChainFactory;
import io.wanjune.minilottery.service.rule.tree.TreeFactory;
import io.wanjune.minilottery.service.vo.DrawResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 抽奖服务实现
 *
 * @author zjh
 * @since 2026/3/10 19:25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryServiceImpl implements LotteryService {

    private final ActivityMapper activityMapper;
    private final AwardMapper awardMapper;
    private final LotteryOrderMapper lotteryOrderMapper;
    private final UserParticipateCountMapper userParticipateCountMapper;
    private final StockService stockService;
    private final MultiLevelCacheService cacheService;
    private final AwardTaskMapper awardTaskMapper;
    private final MQProducer mqProducer;
    private final StrategyArmory strategyArmory;
    private final ChainFactory chainFactory;
    private final TreeFactory treeFactory;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DrawResultVO draw(String userId, String activityId) {
        log.info("抽奖请求 userId={}, activityId={}", userId, activityId);

        // 1. 查询活动信息（多级缓存）
        Activity activity = cacheService.getActivity(activityId);

        // 2. 校验活动状态
        if (activity == null) {
            throw new BusinessException(1001, "活动不存在");
        }
        if (activity.getStatus() != ActivityStatus.ACTIVE.getCode()) {
            throw new BusinessException(1002, "活动未开始或已结束");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getBeginTime()) || now.isAfter(activity.getEndTime())) {
            throw new BusinessException(1003, "活动不在有效期");
        }
        log.info("活动校验通过 activity={}, remainStock={}", activity.getActivityName(), activity.getRemainStock());

        // 3. 校验用户参与资格
        int count = userParticipateCountMapper.queryByUserIdAndActivityId(userId, activityId);
        if (count >= activity.getMaxPerUser()) {
            throw new BusinessException(1004, "已达最大参与次数");
        }
        log.info("用户资格校验通过 已参与{}次, 上限{}次", count, activity.getMaxPerUser());

        // 4. Redis DECR 原子扣减库存（Phase 2 改造：Redisson 锁 → DECR + SETNX）
        //    改造前：Redisson tryLock → DB UPDATE → 释放锁（串行）
        //    改造后：Redis DECR 原子扣减 → SETNX 分段锁 → MQ 异步落库 DB（无锁并行）
        boolean success = stockService.deductStock(activityId, activity.getEndTime());
        if (!success) {
            throw new BusinessException(1005, "库存不足或系统繁忙");
        }
        log.info("库存扣减成功");

        // 5. 责任链前置过滤（Phase 3 改造）
        //    改造前：直接调 strategyArmory.draw(activityId) → O(1) 随机抽奖
        //    改造后：BlackList → Weight → Default 链式过滤
        //    黑名单命中 → 直接返回兜底奖品，跳过规则树
        //    权重命中 → 从权重子奖池抽奖
        //    默认 → 从全量奖池抽奖
        ChainFactory.ChainResult chainResult = chainFactory.openLogicChain(activityId).logic(userId, activityId);
        String awardId = chainResult.awardId();
        log.info("责任链结果 logicModel={}, awardId={}", chainResult.logicModel(), awardId);

        // 6. 规则树后置决策（Phase 3 改造）
        //    仅对 Weight/Default 结果执行（黑名单已经确定了奖品，不需要再走树）
        //    树流程：rule_lock（次数锁）→ rule_stock（奖品库存）→ rule_luck_award（兜底）
        if (chainResult.logicModel() != ChainFactory.LogicModel.RULE_BLACKLIST) {
            // 查询该奖品是否配置了规则树
            List<Award> awards = cacheService.getAwards(activityId);
            Award tempAward = awards.stream()
                    .filter(a -> a.getAwardId().equals(awardId))
                    .findFirst()
                    .orElse(null);
            if (tempAward != null && tempAward.getRuleModels() != null && !tempAward.getRuleModels().isBlank()) {
                // 有规则树配置 → 执行决策树
                String treeId = tempAward.getRuleModels().trim();
                awardId = treeFactory.process(treeId, userId, activityId, awardId);
                log.info("规则树决策后 awardId={}", awardId);
            }
        }

        // 查找最终奖品的完整信息（用于写订单）
        List<Award> awards = cacheService.getAwards(activityId);
        Award award = awards.stream()
                .filter(a -> a.getAwardId().equals(awardId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(1001, "奖品不存在: " + awardId));
        log.info("最终奖品 awardId={}, awardName={}", award.getAwardId(), award.getAwardName());

        // 6. 写入抽奖订单（status=0 待处理，等发奖完成后改为 1）
        String orderId = UUID.randomUUID().toString().replace("-", "");
        LotteryOrder order = LotteryOrder.builder()
                .orderId(orderId)
                .userId(userId)
                .activityId(activityId)
                .awardId(award.getAwardId())
                .awardName(award.getAwardName())
                .status(OrderStatus.PENDING.getCode())
                .expireTime(LocalDateTime.now().plusMinutes(10))
                .build();
        lotteryOrderMapper.insert(order);
        log.info("订单写入成功 orderId={}", orderId);

        // 7. 更新参与次数
        UserParticipateCount participateCount = UserParticipateCount.builder()
                .userId(userId)
                .activityId(activityId)
                .build();
        userParticipateCountMapper.insertOrUpdate(participateCount);

        // 8. 写入发奖任务（供 AwardConsumer 消费时查询）
        AwardTask awardTask = AwardTask.builder()
                .orderId(orderId)
                .userId(userId)
                .awardId(award.getAwardId())
                .awardType(award.getAwardType())
                .status(TaskStatus.PENDING.getCode())
                .retryCount(0)
                .build();
        awardTaskMapper.insert(awardTask);

        // 9. 注册事务同步回调
        //    afterCommit：事务成功 → 发 MQ（发奖 + 延迟超时 + 异步落库 DB）
        //    afterCompletion(ROLLED_BACK)：事务回滚 → 补偿 Redis 库存（INCR 回去）
        //
        //    为什么需要 afterCompletion 补偿？
        //    Redis DECR 发生在事务内（第 4 步），但 Redis 操作不在 Spring 事务管理范围内。
        //    如果后续 DB 操作（写订单、写任务）失败导致事务回滚，Redis 库存已经被扣了，
        //    必须手动 INCR 补回来，否则 Redis 和 DB 库存会永久不一致。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mqProducer.sendAwardMessage(orderId);
                mqProducer.sendOrderDelayMessage(orderId);
                mqProducer.sendStockUpdateMessage(activityId);
                log.info("事务提交后 MQ 消息已发送 orderId={}", orderId);
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    // 事务回滚 → Redis 库存已被 DECR（第 4 步），需要 INCR 补偿回去
                    stockService.rollbackStock(activityId);
                    log.warn("事务回滚，Redis 库存已补偿 INCR activityId={}", activityId);
                }
            }
        });

        // 10. 组装返回结果
        DrawResultVO result = DrawResultVO.builder()
                .awardId(award.getAwardId())
                .awardName(award.getAwardName())
                .awardType(award.getAwardType())
                .build();
        log.info("抽奖完成 userId={}, result={}", userId, result);
        return result;
    }

    @Override
    public List<LotteryOrder> queryRecords(String userId, String activityId) {
        log.info("查询抽奖记录 userId={}, activityId={}", userId, activityId);
        return lotteryOrderMapper.queryByUserIdAndActivityId(userId, activityId);
    }
}
