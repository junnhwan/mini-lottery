package io.wanjune.minilottery.mq.consumer;

import io.wanjune.minilottery.config.RabbitMQConfig;
import io.wanjune.minilottery.lock.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 库存异步落库消费者（Phase 2 新增）
 *
 * 监听 stock.update.queue，将 Redis 中已扣减的库存同步更新到 DB
 *
 * 异步落库流程：
 * 1. 抽奖时 Redis DECR 原子扣减（微秒级，在抽奖接口内完成）
 * 2. 事务提交后发送 stock.update 消息到 MQ
 * 3. 本消费者收到消息 → 调用 DB 更新：UPDATE remain_stock = remain_stock - 1 WHERE remain_stock > 0
 *
 * 为什么要异步落库而不是在抽奖事务内直接更新 DB？
 * - 抽奖链路只依赖 Redis，不依赖 DB 库存写入，减少事务持有时间
 * - DB 乐观锁 WHERE remain_stock > 0 兜底，即使延迟也不会扣成负数
 * - Redis 是实时库存（供扣减用），DB 是持久化库存（供查询和审计用）
 *
 * 幂等性说明：
 * - 如果消息重复消费，DB 执行多次 remain_stock - 1，可能导致库存多扣
 * - 但 WHERE remain_stock > 0 保证不会扣成负数
 * - 极端情况下 Redis 和 DB 库存可能有 1~2 的偏差，属于可接受的最终一致性误差
 * - 如需严格幂等，可引入消息去重表（本项目规模不需要）
 *
 * @author zjh
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockUpdateConsumer {

    private final StockService stockService;

    /**
     * 监听库存异步落库队列
     *
     * @param activityId 活动ID（消息内容就是 activityId）
     */
    @RabbitListener(queues = RabbitMQConfig.STOCK_UPDATE_QUEUE)
    public void onMessage(String activityId) {
        log.info("收到库存异步落库消息 activityId={}", activityId);

        try {
            boolean success = stockService.updateDbStock(activityId);
            if (success) {
                log.info("库存异步落库成功 activityId={}", activityId);
            } else {
                // DB 库存已为 0（WHERE remain_stock > 0 不满足）
                // 这是正常情况：可能是并发扣减导致 DB 比 Redis 先到 0
                log.warn("库存异步落库跳过（DB 库存可能已为 0）activityId={}", activityId);
            }
        } catch (Exception e) {
            // 记录异常，RabbitMQ 会根据配置决定是否重试
            log.error("库存异步落库失败 activityId={}", activityId, e);
            throw e;
        }
    }
}
