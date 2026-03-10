package io.wanjune.minilottery.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解
 *
 * 使用方式：贴在 Controller 方法上即可生效
 * 示例：@RateLimit(permits = 5, window = 60) 表示 60 秒内最多允许 5 次请求
 *
 * 实现原理：
 * - AOP 切面拦截带此注解的方法
 * - 使用 Redis ZSET + 滑动窗口算法判断是否超限
 * - 限流粒度：按 userId 限流（从请求参数中提取）
 *
 * 面试点：
 * - 为什么选滑动窗口而不是固定窗口？→ 固定窗口有边界突刺问题（窗口切换瞬间可能涌入 2 倍流量）
 * - 为什么用 Redis ZSET？→ ZREMRANGEBYSCORE 天然支持按时间范围清理，ZCARD 统计窗口内请求数
 * - 和令牌桶的区别？→ 令牌桶允许突发流量（桶里攒了令牌），滑动窗口严格控制窗口内总量
 *
 * @author zjh
 * @since 2026/3/10
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 窗口内最大请求数
     * 例：permits = 5 表示窗口期内最多 5 次
     */
    int permits() default 5;

    /**
     * 时间窗口，单位：秒
     * 例：window = 60 表示 60 秒的滑动窗口
     */
    int window() default 60;

    /**
     * 限流 key 前缀，默认使用方法路径
     * 最终 Redis key = rate_limit:{prefix}:{userId}
     */
    String prefix() default "";
}
