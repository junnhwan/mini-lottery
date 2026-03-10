package io.wanjune.minilottery.service.impl;

import io.wanjune.minilottery.mapper.ActivityMapper;
import io.wanjune.minilottery.mapper.AwardMapper;
import io.wanjune.minilottery.mapper.LotteryOrderMapper;
import io.wanjune.minilottery.mapper.UserParticipateCountMapper;
import io.wanjune.minilottery.mapper.po.Activity;
import io.wanjune.minilottery.mapper.po.Award;
import io.wanjune.minilottery.mapper.po.LotteryOrder;
import io.wanjune.minilottery.mapper.po.UserParticipateCount;
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

    @Override
    public DrawResultVO draw(String userId, String activityId) {
        log.info("抽奖请求 userId={}, activityId={}", userId, activityId);

        // 1. 查询活动信息
        Activity activity = activityMapper.queryByActivityId(activityId);

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

        // 4. 扣减库存
        int affected = activityMapper.deductStock(activityId);
        if (affected <= 0) {
            throw new RuntimeException("库存不足");
        }
        log.info("库存扣减成功");

        // 5. 执行抽奖
        List<Award> awards = awardMapper.queryByActivityId(activityId);
        Award award = drawAlgorithm.draw(awards);
        log.info("抽奖结果 awardId={}, awardName={}", award.getAwardId(), award.getAwardName());

        // 6. 写入抽奖订单
        LotteryOrder order = LotteryOrder.builder()
                .orderId(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId)
                .activityId(activityId)
                .awardId(award.getAwardId())
                .awardName(award.getAwardName())
                .status(1)
                .build();
        lotteryOrderMapper.insert(order);
        log.info("订单写入成功 orderId={}", order.getOrderId());

        // 7. 更新参与次数
        UserParticipateCount participateCount = UserParticipateCount.builder()
                .userId(userId)
                .activityId(activityId)
                .build();
        userParticipateCountMapper.insertOrUpdate(participateCount);

        // 8. 组装返回结果
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
