package io.wanjune.minilottery.mq.consumer;

import io.wanjune.minilottery.common.enums.OrderStatus;
import io.wanjune.minilottery.config.RabbitMQConfig;
import io.wanjune.minilottery.mapper.ActivityMapper;
import io.wanjune.minilottery.mapper.LotteryOrderMapper;
import io.wanjune.minilottery.mapper.po.LotteryOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单超时消费者
 *
 * 监听 order.timeout.queue（死信队列），处理超时未完成的订单
 *
 * 完整延迟队列流程：
 * 1. 抽奖时，订单状态设为 0（待处理），同时发延迟消息到 order.delay.queue
 * 2. 消息在 delay.queue 中等待 10 分钟（TTL）
 * 3. 10 分钟后，消息过期，被 RabbitMQ 自动转发到死信交换机 → order.timeout.queue
 * 4. 本消费者收到消息，检查订单状态：
 *    - status=0（待处理）→ 说明 10 分钟内没完成发奖 → 回滚库存 + 标记超时
 *    - status=1（已完成）→ 说明已经正常发奖了 → 不做任何处理
 *    - status=2（已超时）→ 说明已经处理过了 → 幂等跳过
 *
 * 面试点：
 * - 为什么不用定时扫表？
 *   → 扫表需要轮询 DB，有延迟（扫描间隔）、浪费资源（空转查询）、不精确
 *   → 延迟队列精确到毫秒，消息到期立刻触发，无需轮询
 * - 消息丢了怎么办？
 *   → 队列持久化（durable=true）+ 消息持久化（默认 persistent）
 *   → 极端情况：补偿机制，定时扫表兜底（低频扫描作为最后防线）
 *
 * @author zjh
 * @since 2026/3/10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private final LotteryOrderMapper lotteryOrderMapper;
    private final ActivityMapper activityMapper;

    /**
     * 监听死信队列，处理超时订单
     *
     * 这个方法在消息经过 10 分钟延迟后才会被调用
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_TIMEOUT_QUEUE)
    public void onMessage(String orderId) {
        log.info("收到超时订单消息 orderId={}", orderId);

        try {
            // 1. 查询订单当前状态
            LotteryOrder order = lotteryOrderMapper.queryByOrderId(orderId);
            if (order == null) {
                log.error("订单不存在 orderId={}", orderId);
                return;
            }

            // 2. 判断订单状态
            if (order.getStatus() != OrderStatus.PENDING.getCode()) {
                // 非"待处理"状态，说明已经完成或已经超时处理过，幂等跳过
                log.info("订单非待处理状态，跳过 orderId={}, status={}", orderId, order.getStatus());
                return;
            }

            // 3. 超时处理：回滚库存 + 更新订单状态
            // 3.1 回滚库存（remain_stock + 1）
            activityMapper.rollbackStock(order.getActivityId());
            log.info("库存已回滚 activityId={}", order.getActivityId());

            // 3.2 更新订单状态为"已超时取消"（status=2）
            lotteryOrderMapper.updateStatus(orderId, OrderStatus.TIMEOUT.getCode());
            log.info("订单已标记超时 orderId={}", orderId);

        } catch (Exception e) {
            log.error("处理超时订单失败 orderId={}", orderId, e);
        }
    }
}
