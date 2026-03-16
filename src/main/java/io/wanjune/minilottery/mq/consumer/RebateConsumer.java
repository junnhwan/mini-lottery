package io.wanjune.minilottery.mq.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wanjune.minilottery.config.RabbitMQConfig;
import io.wanjune.minilottery.mapper.UserParticipateCountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 返利消息消费者 — 签到返利 + 积分兑换 → 增加抽奖机会
 *
 * Phase 5 新增：借鉴 big-market 的 RebateMessageCustomer
 *
 * 消息流程：
 * 1. UserBehaviorService 签到/兑换 → 事务提交后发 MQ
 * 2. 本消费者收到 JSON 消息 → 解析 type
 * 3. 调用 addBonusCount 增加用户的额外抽奖机会
 *
 * 幂等说明：
 * - bonus_count 是累加操作（+= rewardCount），重复消费会导致多加
 * - 生产环境应通过消息 ID 去重（Redis SET + 过期时间）
 * - 当前简化版可接受：最多多给 1 次抽奖机会，不影响业务正确性
 *
 * @author zjh
 * @since 2026/3/16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RebateConsumer {

    private final UserParticipateCountMapper userParticipateCountMapper;
    private final ObjectMapper objectMapper;

    /**
     * 监听返利队列
     *
     * 消息体 JSON 格式：
     * {
     *   "userId": "user001",
     *   "activityId": "A20260310001",
     *   "type": "SIGN_IN" / "CREDIT_EXCHANGE",
     *   "rewardCount": 1
     * }
     */
    @RabbitListener(queues = RabbitMQConfig.REBATE_QUEUE)
    public void onMessage(String message) {
        log.info("收到返利消息 message={}", message);

        try {
            JsonNode json = objectMapper.readTree(message);
            String userId = json.get("userId").asText();
            String activityId = json.get("activityId").asText();
            String type = json.get("type").asText();
            int rewardCount = json.get("rewardCount").asInt();

            // 根据类型处理（当前 SIGN_IN 和 CREDIT_EXCHANGE 逻辑一致，都是增加抽奖机会）
            // 面试点：为什么还要区分 type？
            // → 方便后续扩展不同的返利策略（如签到给 1 次，兑换给 2 次），也方便日志追踪
            switch (type) {
                case "SIGN_IN" -> {
                    userParticipateCountMapper.addBonusCount(userId, activityId, rewardCount);
                    log.info("签到返利完成 userId={}, activityId={}, bonus=+{}", userId, activityId, rewardCount);
                }
                case "CREDIT_EXCHANGE" -> {
                    userParticipateCountMapper.addBonusCount(userId, activityId, rewardCount);
                    log.info("积分兑换返利完成 userId={}, activityId={}, bonus=+{}", userId, activityId, rewardCount);
                }
                default -> log.warn("未知返利类型 type={}", type);
            }

        } catch (Exception e) {
            log.error("返利消息处理失败 message={}", message, e);
            // 抛出异常让 RabbitMQ 重试
            throw new RuntimeException("返利消息处理失败", e);
        }
    }
}
