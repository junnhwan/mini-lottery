package io.wanjune.minilottery.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置 — 交换机、队列、绑定关系
 *
 * 整体架构：
 *
 * 1. 发奖流程（直接消费）：
 *    Producer → award.exchange → award.queue → AwardConsumer（执行发奖）
 *
 * 2. 订单超时流程（延迟队列 = TTL + 死信）：
 *    Producer → order.delay.exchange → order.delay.queue（消息在这里等 10 分钟）
 *                                          ↓ 消息过期（TTL 到期）
 *                                     order.timeout.exchange → order.timeout.queue → OrderTimeoutConsumer（回滚库存）
 *
 * 延迟队列原理：
 * - RabbitMQ 本身不支持延迟队列，但可以通过 "TTL + 死信" 实现
 * - TTL（Time To Live）：给队列中的消息设置存活时间，到期后消息"死亡"
 * - 死信交换机（DLX）：消息死亡后不会被丢弃，而是转发到绑定的死信交换机
 * - 死信队列：死信交换机路由到的队列，消费者监听这个队列就能在"延迟"后收到消息
 *
 * 面试点：
 * - 为什么不用定时扫表？→ 扫表有延迟、占 DB 资源、不精确
 * - 消息丢了怎么办？→ 发送端 confirm + 消费端手动 ack + 持久化
 * - 为什么不用 RabbitMQ 的延迟插件？→ 插件需要额外安装，TTL+DLX 是原生方案更通用
 *
 * @author zjh
 * @since 2026/3/10
 */
@Configuration
public class RabbitMQConfig {

    // ======================== 常量定义 ========================

    /** 发奖交换机 */
    public static final String AWARD_EXCHANGE = "award.exchange";
    /** 发奖队列 */
    public static final String AWARD_QUEUE = "award.queue";
    /** 发奖路由键 */
    public static final String AWARD_ROUTING_KEY = "award.routing";

    /** 延迟队列交换机（消息先到这里） */
    public static final String ORDER_DELAY_EXCHANGE = "order.delay.exchange";
    /** 延迟队列（消息在此等待 TTL 过期） */
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";
    /** 延迟队列路由键 */
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay.routing";

    /** 死信交换机（消息过期后转发到这里） */
    public static final String ORDER_TIMEOUT_EXCHANGE = "order.timeout.exchange";
    /** 死信队列（消费者监听这个队列处理超时订单） */
    public static final String ORDER_TIMEOUT_QUEUE = "order.timeout.queue";
    /** 死信路由键 */
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "order.timeout.routing";

    /** 库存异步落库交换机（Phase 2 新增：DECR 后异步更新 DB 库存） */
    public static final String STOCK_UPDATE_EXCHANGE = "stock.update.exchange";
    /** 库存异步落库队列 */
    public static final String STOCK_UPDATE_QUEUE = "stock.update.queue";
    /** 库存异步落库路由键 */
    public static final String STOCK_UPDATE_ROUTING_KEY = "stock.update.routing";

    /** 返利交换机（Phase 5 新增：签到返利 + 积分兑换 → 增加抽奖机会） */
    public static final String REBATE_EXCHANGE = "rebate.exchange";
    /** 返利队列 */
    public static final String REBATE_QUEUE = "rebate.queue";
    /** 返利路由键 */
    public static final String REBATE_ROUTING_KEY = "rebate.routing";

    /** 订单超时时间：10 分钟（毫秒） */
    public static final int ORDER_TTL = 10 * 60 * 1000;

    // ======================== 发奖队列配置 ========================

    @Bean
    public DirectExchange awardExchange() {
        return new DirectExchange(AWARD_EXCHANGE);
    }

    @Bean
    public Queue awardQueue() {
        return QueueBuilder.durable(AWARD_QUEUE).build();
    }

    @Bean
    public Binding awardBinding() {
        return BindingBuilder.bind(awardQueue()).to(awardExchange()).with(AWARD_ROUTING_KEY);
    }

    // ======================== 库存异步落库队列配置（Phase 2 新增） ========================

    /**
     * 库存异步落库交换机
     *
     * Phase 2 改造：Redis DECR 扣减库存后，通过 MQ 异步更新 DB
     * 目的：解耦 Redis 扣减和 DB 更新，提高抽奖接口响应速度
     */
    @Bean
    public DirectExchange stockUpdateExchange() {
        return new DirectExchange(STOCK_UPDATE_EXCHANGE);
    }

    @Bean
    public Queue stockUpdateQueue() {
        return QueueBuilder.durable(STOCK_UPDATE_QUEUE).build();
    }

    @Bean
    public Binding stockUpdateBinding() {
        return BindingBuilder.bind(stockUpdateQueue()).to(stockUpdateExchange()).with(STOCK_UPDATE_ROUTING_KEY);
    }

    // ======================== 返利队列配置（Phase 5 新增） ========================

    /**
     * 返利交换机
     *
     * Phase 5 新增：签到返利 / 积分兑换 → 发 MQ → RebateConsumer 增加抽奖机会
     * 消息体为 JSON（包含 userId、activityId、type、rewardCount）
     */
    @Bean
    public DirectExchange rebateExchange() {
        return new DirectExchange(REBATE_EXCHANGE);
    }

    @Bean
    public Queue rebateQueue() {
        return QueueBuilder.durable(REBATE_QUEUE).build();
    }

    @Bean
    public Binding rebateBinding() {
        return BindingBuilder.bind(rebateQueue()).to(rebateExchange()).with(REBATE_ROUTING_KEY);
    }

    // ======================== 延迟队列配置（TTL + 死信） ========================

    /**
     * 死信交换机 — 接收延迟队列中过期的消息
     */
    @Bean
    public DirectExchange orderTimeoutExchange() {
        return new DirectExchange(ORDER_TIMEOUT_EXCHANGE);
    }

    /**
     * 死信队列 — 消费者监听此队列，处理超时订单
     */
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(ORDER_TIMEOUT_QUEUE).build();
    }

    @Bean
    public Binding orderTimeoutBinding() {
        return BindingBuilder.bind(orderTimeoutQueue()).to(orderTimeoutExchange()).with(ORDER_TIMEOUT_ROUTING_KEY);
    }

    /**
     * 延迟队列交换机
     */
    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(ORDER_DELAY_EXCHANGE);
    }

    /**
     * 延迟队列 — 消息在此停留 TTL 时间后，自动转发到死信交换机
     *
     * 关键配置：
     * - x-message-ttl：消息存活时间（10 分钟）
     * - x-dead-letter-exchange：消息过期后转发到哪个交换机
     * - x-dead-letter-routing-key：转发时使用的路由键
     */
    @Bean
    public Queue orderDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", ORDER_TTL);                                    // 消息 10 分钟后过期
        args.put("x-dead-letter-exchange", ORDER_TIMEOUT_EXCHANGE);               // 过期后转发到死信交换机
        args.put("x-dead-letter-routing-key", ORDER_TIMEOUT_ROUTING_KEY);         // 转发使用的路由键
        return QueueBuilder.durable(ORDER_DELAY_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue()).to(orderDelayExchange()).with(ORDER_DELAY_ROUTING_KEY);
    }
}
