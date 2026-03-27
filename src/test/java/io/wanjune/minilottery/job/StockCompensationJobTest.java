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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockCompensationJobTest {

    @InjectMocks
    private StockCompensationJob stockCompensationJob;

    @Mock
    private ActivityMapper activityMapper;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RAtomicLong atomicLong;

    @Test
    void compensate_shouldSyncDbStockWhenDiffExceedsThreshold() {
        Activity activity = new Activity();
        activity.setActivityId("A003");
        activity.setRemainStock(10);

        ReflectionTestUtils.setField(stockCompensationJob, "diffThreshold", 2);

        when(activityMapper.queryByStatus(ActivityStatus.ACTIVE.getCode())).thenReturn(List.of(activity));
        when(redissonClient.getAtomicLong("stock:A003")).thenReturn(atomicLong);
        when(atomicLong.isExists()).thenReturn(true);
        when(atomicLong.get()).thenReturn(7L);
        when(activityMapper.syncRemainStock("A003", 7)).thenReturn(1);

        stockCompensationJob.compensate();

        verify(activityMapper).syncRemainStock(eq("A003"), eq(7));
    }

    @Test
    void compensate_shouldSkipMinorDrift() {
        Activity activity = new Activity();
        activity.setActivityId("A004");
        activity.setRemainStock(10);

        ReflectionTestUtils.setField(stockCompensationJob, "diffThreshold", 2);

        when(activityMapper.queryByStatus(ActivityStatus.ACTIVE.getCode())).thenReturn(List.of(activity));
        when(redissonClient.getAtomicLong("stock:A004")).thenReturn(atomicLong);
        when(atomicLong.isExists()).thenReturn(true);
        when(atomicLong.get()).thenReturn(9L);

        stockCompensationJob.compensate();

        verify(activityMapper, never()).syncRemainStock(eq("A004"), eq(9));
    }
}
