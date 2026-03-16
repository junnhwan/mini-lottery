package io.wanjune.minilottery.mq.producer;

import io.wanjune.minilottery.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * MQ 消息生产者
 *
 * 负责发送三类消息：
 * 1. 发奖消息 → award.queue → AwardConsumer 异步执行发奖
 * 2. 库存异步落库消息 → stock.update.queue → StockUpdateConsumer 更新 DB 库存（Phase 2 新增）
 * 3. 延迟订单消息 → order.delay.queue（等 10min）→ order.timeout.queue → OrderTimeoutConsumer 检查超时
 * 4. 返利消息 → rebate.queue → RebateConsumer 增加抽奖机会（Phase 5 新增）
 *
 * 为什么要异步发奖？
 * - 发奖可能涉及调用第三方接口（优惠券系统、物流系统），耗时不确定
 * - 同步发奖会拖慢抽奖接口响应时间
 * - MQ 天然支持重试，发奖失败可以重新消费
 *
 * @author zjh
 * @since 2026/3/10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MQProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送发奖消息
     *
     * @param orderId 订单号（消费者通过 orderId 查 award_task 表获取发奖信息）
     */
    public void sendAwardMessage(String orderId) {
        log.info("发送发奖消息 orderId={}", orderId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.AWARD_EXCHANGE,
                RabbitMQConfig.AWARD_ROUTING_KEY,
                orderId
        );
    }

    /**
     * 发送库存异步落库消息（Phase 2 新增）
     *
     * Redis DECR 扣减成功后，通过 MQ 异步更新 DB 的 remain_stock
     * 为什么不在抽奖事务内直接更新 DB 库存？
     * - DECR 是原子操作，微秒级完成
     * - DB UPDATE 涉及行锁 + WAL 写入，毫秒级
     * - 分离后抽奖链路不依赖 DB 库存写入，响应更快
     *
     * @param activityId 活动ID
     */
    public void sendStockUpdateMessage(String activityId) {
        log.info("发送库存异步落库消息 activityId={}", activityId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.STOCK_UPDATE_EXCHANGE,
                RabbitMQConfig.STOCK_UPDATE_ROUTING_KEY,
                activityId
        );
    }

    /**
     * 发送延迟订单消息
     *
     * 消息发到 order.delay.queue 后，会在队列中停留 TTL（10 分钟）
     * 到期后自动转发到死信队列 order.timeout.queue，由 OrderTimeoutConsumer 处理
     *
     * 注意：TTL 在队列级别配置（RabbitMQConfig 中的 x-message-ttl），
     * 所以这里不需要给单条消息设置过期时间
     *
     * @param orderId 订单号
     */
    public void sendOrderDelayMessage(String orderId) {
        log.info("发送延迟订单消息 orderId={}, TTL={}ms", orderId, RabbitMQConfig.ORDER_TTL);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_DELAY_EXCHANGE,
                RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                orderId
        );
    }

    /**
     * 发送返利消息（Phase 5 新增）
     *
     * 签到返利 / 积分兑换后，通过 MQ 异步增加用户的额外抽奖机会
     * 消息体为 JSON 字符串，包含 userId、activityId、type、rewardCount
     *
     * 为什么用 MQ 而不是同步调用？
     * - 签到/兑换操作和增加抽奖机会是两个独立业务，MQ 解耦
     * - 即使 Consumer 暂时不可用，消息会在队列中等待，保证最终一致
     *
     * @param jsonMessage JSON 格式消息体
     */
    public void sendRebateMessage(String jsonMessage) {
        log.info("发送返利消息 message={}", jsonMessage);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.REBATE_EXCHANGE,
                RabbitMQConfig.REBATE_ROUTING_KEY,
                jsonMessage
        );
    }
}
