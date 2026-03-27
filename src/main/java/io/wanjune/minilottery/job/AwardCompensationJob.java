package io.wanjune.minilottery.job;

import io.wanjune.minilottery.common.enums.OrderStatus;
import io.wanjune.minilottery.common.enums.TaskStatus;
import io.wanjune.minilottery.mapper.AwardTaskMapper;
import io.wanjune.minilottery.mapper.LotteryOrderMapper;
import io.wanjune.minilottery.mapper.po.AwardTask;
import io.wanjune.minilottery.mapper.po.LotteryOrder;
import io.wanjune.minilottery.mq.producer.MQProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwardCompensationJob {

    private final AwardTaskMapper awardTaskMapper;
    private final LotteryOrderMapper lotteryOrderMapper;
    private final MQProducer mqProducer;

    @Value("${mini-lottery.jobs.award-compensation-stale-minutes:2}")
    private long staleMinutes = 2;

    @Value("${mini-lottery.jobs.award-compensation-max-retry-count:3}")
    private int maxRetryCount = 3;

    @Value("${mini-lottery.jobs.award-compensation-batch-size:100}")
    private int batchSize = 100;

    @Scheduled(fixedDelayString = "${mini-lottery.jobs.award-compensation-delay-ms:60000}")
    public void compensate() {
        List<AwardTask> failedTasks = awardTaskMapper.queryRetryableFailedTasks(
                TaskStatus.FAILED.getCode(), maxRetryCount, batchSize);

        LocalDateTime now = LocalDateTime.now();
        List<LotteryOrder> staleOrders = lotteryOrderMapper.queryStalePendingOrders(
                OrderStatus.PENDING.getCode(), now.minusMinutes(staleMinutes), now, batchSize);

        Set<String> orderIds = new LinkedHashSet<>();
        failedTasks.stream().map(AwardTask::getOrderId).forEach(orderIds::add);
        staleOrders.stream().map(LotteryOrder::getOrderId).forEach(orderIds::add);

        if (orderIds.isEmpty()) {
            return;
        }

        log.info("Award compensation triggered, republishing {} orders", orderIds.size());
        for (String orderId : orderIds) {
            mqProducer.sendAwardMessage(orderId);
        }
    }
}
