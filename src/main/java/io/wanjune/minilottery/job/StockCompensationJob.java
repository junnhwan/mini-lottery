package io.wanjune.minilottery.job;

import io.wanjune.minilottery.common.enums.ActivityStatus;
import io.wanjune.minilottery.mapper.ActivityMapper;
import io.wanjune.minilottery.mapper.po.Activity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存补偿任务
 *
 * 当 Redis 实时库存与 DB 持久化库存出现明显偏差时，以 Redis 快照为准修正 DB，
 * 作为 MQ 异步落库链路之外的最后一道兜底补偿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCompensationJob {

    private static final String STOCK_KEY_PREFIX = "stock:";

    private final ActivityMapper activityMapper;
    private final RedissonClient redissonClient;

    @Value("${mini-lottery.jobs.stock-reconcile-diff-threshold:2}")
    private int diffThreshold = 2;

    @Scheduled(fixedDelayString = "${mini-lottery.jobs.stock-reconcile-delay-ms:300000}")
    public void compensate() {
        List<Activity> activeActivities = activityMapper.queryByStatus(ActivityStatus.ACTIVE.getCode());
        for (Activity activity : activeActivities) {
            String activityId = activity.getActivityId();
            RAtomicLong atomicLong = redissonClient.getAtomicLong(STOCK_KEY_PREFIX + activityId);
            if (!atomicLong.isExists()) {
                continue;
            }

            long redisStock = atomicLong.get();
            int dbStock = activity.getRemainStock() == null ? 0 : activity.getRemainStock();
            long diff = Math.abs(redisStock - dbStock);

            if (diff < diffThreshold) {
                continue;
            }

            int targetStock = Math.toIntExact(redisStock);
            int affected = activityMapper.syncRemainStock(activityId, targetStock);
            if (affected > 0) {
                log.warn("Stock compensation applied activityId={}, redisStock={}, dbStock={}, diff={}",
                        activityId, redisStock, dbStock, diff);
            }
        }
    }
}
