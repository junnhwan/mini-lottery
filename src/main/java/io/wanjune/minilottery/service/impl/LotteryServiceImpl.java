package io.wanjune.minilottery.service.impl;

import io.wanjune.minilottery.cache.MultiLevelCacheService;
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
import io.wanjune.minilottery.service.algorithm.DrawAlgorithm;
import io.wanjune.minilottery.service.vo.DrawResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final DrawAlgorithm drawAlgorithm;
    private final StockService stockService;
    private final MultiLevelCacheService cacheService;
    private final AwardTaskMapper awardTaskMapper;
    private final MQProducer mqProducer;

    @Override
    public DrawResultVO draw(String userId, String activityId) {
        log.info("抽奖请求 userId={}, activityId={}", userId, activityId);

        // 1. 查询活动信息（多级缓存）
        Activity activity = cacheService.getActivity(activityId);

        // 2. 校验活动状态
        if (activity == null) {
            throw new RuntimeException("活动不存在");
        }
        if (activity.getStatus() != 1) {
            throw new RuntimeException("活动未开始或已结束");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getBeginTime()) || now.isAfter(activity.getEndTime())) {
            throw new RuntimeException("活动不在有效期");
        }
        log.info("活动校验通过 activity={}, remainStock={}", activity.getActivityName(), activity.getRemainStock());

        // 3. 校验用户参与资格
        int count = userParticipateCountMapper.queryByUserIdAndActivityId(userId, activityId);
        if (count >= activity.getMaxPerUser()) {
            throw new RuntimeException("已达最大参与次数");
        }
        log.info("用户资格校验通过 已参与{}次, 上限{}次", count, activity.getMaxPerUser());

        // 4. 分布式锁扣减库存
        boolean success = stockService.deductStock(activityId);
        if (!success) {
            throw new RuntimeException("库存不足或系统繁忙");
        }
        log.info("库存扣减成功");

        // 5. 执行抽奖（多级缓存）
        List<Award> awards = cacheService.getAwards(activityId);
        Award award = drawAlgorithm.draw(awards);
        log.info("抽奖结果 awardId={}, awardName={}", award.getAwardId(), award.getAwardName());

        // 6. 写入抽奖订单（status=0 待处理，等发奖完成后改为 1）
        String orderId = UUID.randomUUID().toString().replace("-", "");
        LotteryOrder order = LotteryOrder.builder()
                .orderId(orderId)
                .userId(userId)
                .activityId(activityId)
                .awardId(award.getAwardId())
                .awardName(award.getAwardName())
                .status(0)
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
                .status(0)
                .retryCount(0)
                .build();
        awardTaskMapper.insert(awardTask);

        // 9. 发送 MQ 消息：异步发奖 + 延迟订单超时检查
        mqProducer.sendAwardMessage(orderId);
        mqProducer.sendOrderDelayMessage(orderId);
        log.info("MQ 消息已发送 orderId={}", orderId);

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
