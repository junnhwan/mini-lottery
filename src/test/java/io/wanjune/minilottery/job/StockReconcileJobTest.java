package io.wanjune.minilottery.job;

import io.wanjune.minilottery.common.enums.ActivityStatus;
import io.wanjune.minilottery.mapper.ActivityMapper;
import io.wanjune.minilottery.mapper.po.Activity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockReconcileJobTest {

    @InjectMocks
    private StockReconcileJob stockReconcileJob;

    @Mock
    private ActivityMapper activityMapper;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RAtomicLong atomicLong;

    @Test
    void reconcile_shouldReadRedisStockForActiveActivities() {
        Activity activity = new Activity();
        activity.setActivityId("A001");
        activity.setRemainStock(10);

        when(activityMapper.queryByStatus(ActivityStatus.ACTIVE.getCode())).thenReturn(List.of(activity));
        when(redissonClient.getAtomicLong("stock:A001")).thenReturn(atomicLong);
        when(atomicLong.isExists()).thenReturn(true);
        when(atomicLong.get()).thenReturn(11L);

        stockReconcileJob.reconcile();

        verify(redissonClient).getAtomicLong("stock:A001");
        verify(atomicLong).get();
    }

    @Test
    void reconcile_shouldSkipMissingRedisStockKey() {
        Activity activity = new Activity();
        activity.setActivityId("A002");
        activity.setRemainStock(8);

        when(activityMapper.queryByStatus(ActivityStatus.ACTIVE.getCode())).thenReturn(List.of(activity));
        when(redissonClient.getAtomicLong("stock:A002")).thenReturn(atomicLong);
        when(atomicLong.isExists()).thenReturn(false);

        stockReconcileJob.reconcile();

        verify(atomicLong, never()).get();
    }
}
