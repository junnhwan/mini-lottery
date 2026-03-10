package io.wanjune.minilottery.mq.consumer;

import io.wanjune.minilottery.config.RabbitMQConfig;
import io.wanjune.minilottery.mapper.AwardTaskMapper;
import io.wanjune.minilottery.mapper.LotteryOrderMapper;
import io.wanjune.minilottery.mapper.po.AwardTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 发奖消息消费者
 *
 * 监听 award.queue，收到消息后执行发奖逻辑
 *
 * 异步发奖流程：
 * 1. 收到 orderId 消息
 * 2. 查询 award_task 表获取发奖信息
 * 3. 根据 awardType 执行不同的发奖逻辑（优惠券/实物/谢谢参与）
 * 4. 更新 award_task 状态为"已完成"
 * 5. 发奖失败 → 增加重试次数，消息重新入队（由 RabbitMQ 自动重试）
 *
 * 面试点：
 * - 幂等性：消费者可能收到重复消息（网络抖动、重试），所以要先检查 award_task 状态
 *   如果已经是"已完成"状态，直接跳过，防止重复发奖
 * - 消息可靠性：RabbitMQ 默认 auto-ack，消费失败消息就丢了
 *   生产环境应改为手动 ack（acknowledge-mode: manual）
 *
 * @author zjh
 * @since 2026/3/10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwardConsumer {

    private final AwardTaskMapper awardTaskMapper;
    private final LotteryOrderMapper lotteryOrderMapper;

    /**
     * 监听发奖队列
     *
     * @RabbitListener 注解让这个方法自动成为 award.queue 的消费者
     * Spring 收到消息后会调用这个方法，参数就是消息内容（orderId）
     */
    @RabbitListener(queues = RabbitMQConfig.AWARD_QUEUE)
    public void onMessage(String orderId) {
        log.info("收到发奖消息 orderId={}", orderId);

        try {
            // 1. 查询发奖任务
            AwardTask task = awardTaskMapper.queryByOrderId(orderId);
            if (task == null) {
                log.error("发奖任务不存在 orderId={}", orderId);
                return;
            }

            // 2. 幂等检查：已完成的任务不重复处理
            if (task.getStatus() == 2) {
                log.info("发奖任务已完成，跳过 orderId={}", orderId);
                return;
            }

            // 3. 更新任务状态为"发送中"
            awardTaskMapper.updateStatus(orderId, 1);

            // 4. 根据奖品类型执行发奖（这里模拟发奖，实际会调用第三方接口）
            switch (task.getAwardType()) {
                case 1 -> log.info("发放优惠券 userId={}, awardId={}", task.getUserId(), task.getAwardId());
                case 2 -> log.info("发放实物奖品 userId={}, awardId={}", task.getUserId(), task.getAwardId());
                case 3 -> log.info("谢谢参与，无需发奖 userId={}", task.getUserId());
            }

            // 5. 更新任务状态为"已完成"
            awardTaskMapper.updateStatus(orderId, 2);

            // 6. 更新订单状态为"已完成"（关键！不改的话 OrderTimeoutConsumer 会误回滚）
            lotteryOrderMapper.updateStatus(orderId, 1);

            log.info("发奖完成 orderId={}", orderId);

        } catch (Exception e) {
            // 发奖失败：增加重试次数，抛出异常让 RabbitMQ 重试
            log.error("发奖失败 orderId={}", orderId, e);
            awardTaskMapper.incrementRetryCount(orderId);
            awardTaskMapper.updateStatus(orderId, 3);  // 标记为失败
        }
    }
}
