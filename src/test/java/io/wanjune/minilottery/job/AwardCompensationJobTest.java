package io.wanjune.minilottery.job;

import io.wanjune.minilottery.common.enums.OrderStatus;
import io.wanjune.minilottery.common.enums.TaskStatus;
import io.wanjune.minilottery.mapper.AwardTaskMapper;
import io.wanjune.minilottery.mapper.LotteryOrderMapper;
import io.wanjune.minilottery.mapper.po.AwardTask;
import io.wanjune.minilottery.mapper.po.LotteryOrder;
import io.wanjune.minilottery.mq.producer.MQProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwardCompensationJobTest {

    @InjectMocks
    private AwardCompensationJob awardCompensationJob;

    @Mock
    private AwardTaskMapper awardTaskMapper;

    @Mock
    private LotteryOrderMapper lotteryOrderMapper;

    @Mock
    private MQProducer mqProducer;

    @Test
    void compensate_shouldRepublishFailedTasksAndStalePendingOrdersWithoutDuplicates() {
        AwardTask failedTask = AwardTask.builder()
                .orderId("order-1")
                .status(TaskStatus.FAILED.getCode())
                .retryCount(1)
                .build();

        LotteryOrder staleOrder = LotteryOrder.builder()
                .orderId("order-1")
                .status(OrderStatus.PENDING.getCode())
                .expireTime(LocalDateTime.now().plusMinutes(5))
                .build();

        LotteryOrder anotherStaleOrder = LotteryOrder.builder()
                .orderId("order-2")
                .status(OrderStatus.PENDING.getCode())
                .expireTime(LocalDateTime.now().plusMinutes(5))
                .build();

        when(awardTaskMapper.queryRetryableFailedTasks(eq(TaskStatus.FAILED.getCode()), eq(3), eq(100)))
                .thenReturn(List.of(failedTask));
        when(lotteryOrderMapper.queryStalePendingOrders(eq(OrderStatus.PENDING.getCode()), any(), any(), eq(100)))
                .thenReturn(List.of(staleOrder, anotherStaleOrder));

        awardCompensationJob.compensate();

        verify(mqProducer, times(1)).sendAwardMessage("order-1");
        verify(mqProducer, times(1)).sendAwardMessage("order-2");
    }
}
