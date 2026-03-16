package io.wanjune.minilottery.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解 — Guava 令牌桶 + 超频自动拉黑 + 兜底方法
 *
 * 使用方式：贴在 Controller 方法上，指定 fallbackMethod（必填）
 * 示例：@RateLimit(permitsPerSecond = 1.0, blacklistCount = 10, fallbackMethod = "drawRateLimitFallback")
 *
 * 改造历程（面试追问点）：
 * - Phase 1（Week 3）：Redis ZSET 滑动窗口（3 次 Redis 调用，非原子操作）
 * - Phase 4（当前）：Guava 令牌桶（JVM 本地，微秒级，零网络开销）
 *
 * 为什么从 Redis 滑动窗口改为 Guava 令牌桶？
 * 1. 性能：Redis 需要 3 次网络调用（ZREMRANGEBYSCORE + ZCARD + ZADD），Guava 纯本地操作
 * 2. 原子性：Redis 方案非原子（3 步操作之间可能被其他请求穿插），Guava tryAcquire 天然原子
 * 3. 令牌桶允许突发（攒的令牌可以一次消耗），比滑动窗口更适合抽奖场景（用户偶尔集中点击）
 * 4. 单实例部署下本地限流完全够用，多实例可在 Nginx 层再加一道
 *
 * @author zjh
 * @since 2026/3/10
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流 key 对应的方法参数名
     * AOP 切面会从方法参数中找到该名称的参数值，作为每用户独立限流的标识
     * 默认 "userId"，即按用户维度限流
     */
    String key() default "userId";

    /**
     * 令牌桶每秒生成的令牌数（Guava RateLimiter 的核心参数）
     *
     * 例：permitsPerSecond = 1.0 表示每秒生成 1 个令牌
     * 令牌会累积（桶未满时），允许短时间突发流量
     * 默认 1.0 QPS
     */
    double permitsPerSecond() default 1.0;

    /**
     * 触发自动拉黑的连续限流次数
     *
     * 例：blacklistCount = 10 表示连续被限流 10 次后，用户被拉黑（24 小时内直接拒绝）
     * 设为 0 表示不启用拉黑功能
     */
    int blacklistCount() default 0;

    /**
     * 兜底方法名（必填）
     *
     * 被限流或拉黑时，AOP 通过反射调用同一 Controller 中的该方法
     * 要求：方法签名必须与被拦截方法完全一致（参数类型 + 返回类型）
     *
     * 为什么用 fallback 而不是直接抛异常？
     * → 异常返回给前端是一个 error，用户体验差
     * → fallback 返回友好提示（如"请稍后再试"），不触发前端错误处理逻辑
     */
    String fallbackMethod();
}
