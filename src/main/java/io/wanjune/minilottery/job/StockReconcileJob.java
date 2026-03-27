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

@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconcileJob {

    private static final String STOCK_KEY_PREFIX = "stock:";

    private final ActivityMapper activityMapper;
    private final RedissonClient redissonClient;

    @Value("${mini-lottery.jobs.stock-reconcile-diff-threshold:2}")
    private int diffThreshold = 2;

    @Scheduled(fixedDelayString = "${mini-lottery.jobs.stock-reconcile-delay-ms:300000}")
    public void reconcile() {
        List<Activity> activeActivities = activityMapper.queryByStatus(ActivityStatus.ACTIVE.getCode());
        for (Activity activity : activeActivities) {
            String stockKey = STOCK_KEY_PREFIX + activity.getActivityId();
            RAtomicLong atomicLong = redissonClient.getAtomicLong(stockKey);
            if (!atomicLong.isExists()) {
                log.warn("Stock reconcile skipped, redis key missing activityId={}", activity.getActivityId());
                continue;
            }

            long redisStock = atomicLong.get();
            int dbStock = activity.getRemainStock() == null ? 0 : activity.getRemainStock();
            long diff = Math.abs(redisStock - dbStock);

            if (diff >= diffThreshold) {
                log.error("Stock reconcile mismatch activityId={}, redisStock={}, dbStock={}, diff={}",
                        activity.getActivityId(), redisStock, dbStock, diff);
            } else if (diff > 0) {
                log.warn("Stock reconcile slight drift activityId={}, redisStock={}, dbStock={}, diff={}",
                        activity.getActivityId(), redisStock, dbStock, diff);
            }
        }
    }
}
