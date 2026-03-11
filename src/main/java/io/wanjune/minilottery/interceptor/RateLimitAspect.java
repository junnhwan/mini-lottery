package io.wanjune.minilottery.interceptor;

import io.wanjune.minilottery.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 限流切面 — 基于 Redis ZSET 的滑动窗口算法
 *
 * 核心原理：
 * 1. Redis ZSET 的 key = rate_limit:{接口路径}:{userId}
 * 2. 每个请求作为一个 member 存入 ZSET，score 为当前时间戳（毫秒）
 * 3. 每次请求先清理窗口外的过期数据（ZREMRANGEBYSCORE）
 * 4. 再统计窗口内的请求数（ZCARD）
 * 5. 未超限 → 添加记录并放行；超限 → 拒绝
 *
 * 滑动窗口 vs 固定窗口：
 * - 固定窗口：把时间切成固定区间（如每分钟），窗口边界处可能瞬间涌入 2 倍流量
 *   例：窗口 0:00~1:00 限 5 次，0:59 来 5 次 + 1:01 来 5 次 = 2 秒内 10 次请求
 * - 滑动窗口：以当前时刻为终点往前推 N 秒，没有边界问题，更平滑
 *
 * 为什么用 ZSET？
 * - ZREMRANGEBYSCORE：O(logN+M) 高效清理过期数据
 * - ZCARD：O(1) 统计当前窗口请求数
 * - ZADD：O(logN) 添加请求记录
 * - TTL：给整个 key 设过期时间，防止用户不再请求后 key 永远残留
 *
 * @author zjh
 * @since 2026/3/10
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String RATE_LIMIT_PREFIX = "rate_limit:";

    /**
     * 环绕通知：拦截所有带 @RateLimit 注解的方法
     *
     * @Around 的执行流程：
     * 1. 方法执行前 → 限流判断
     * 2. 未超限 → joinPoint.proceed() 放行执行原方法
     * 3. 超限 → 直接抛异常，原方法不执行
     */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {

        // --- 1. 构建限流 key ---
        // 从方法参数中提取 userId 作为限流粒度（每个用户独立计数）
        String userId = extractUserId(joinPoint);
        String prefix = rateLimit.prefix().isEmpty()
                ? joinPoint.getSignature().toShortString()  // 默认用方法签名，如 "LotteryController.draw(..)"
                : rateLimit.prefix();
        String key = RATE_LIMIT_PREFIX + prefix + ":" + userId;

        // --- 2. 读取注解配置 ---
        int permits = rateLimit.permits();   // 窗口内最大请求数
        int window = rateLimit.window();     // 时间窗口（秒）

        // --- 3. 滑动窗口算法 ---
        long now = System.currentTimeMillis();
        long windowStart = now - window * 1000L;  // 窗口起始时间

        // 3.1 清理窗口外的过期请求记录
        // ZREMRANGEBYSCORE key -inf windowStart
        // 把 score < windowStart 的 member 全部移除
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

        // 3.2 统计当前窗口内的请求数
        // ZCARD key → 返回 ZSET 中的 member 数量
        Long count = redisTemplate.opsForZSet().zCard(key);

        if (count != null && count >= permits) {
            // --- 4. 超限：拒绝请求 ---
            log.warn("接口限流 key={}, 当前请求数={}, 上限={}", key, count, permits);
            throw new BusinessException(1006, "请求过于频繁，请稍后再试");
        }

        // --- 5. 未超限：记录本次请求并放行 ---
        // ZADD key score member
        // member 用 UUID 保证唯一性（同一毫秒可能有多个请求）
        // score 用当前时间戳，方便按时间范围清理
        redisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);

        // 给 key 设置过期时间 = 窗口大小 + 1 秒（兜底清理，防止 key 永远残留）
        redisTemplate.expire(key, window + 1, TimeUnit.SECONDS);

        log.debug("限流放行 key={}, 当前请求数={}/{}", key, count + 1, permits);

        // --- 6. 执行原方法 ---
        return joinPoint.proceed();
    }

    /**
     * 从方法参数中提取 userId
     *
     * 遍历方法的参数列表，找到名为 "userId" 的参数并返回其值
     * 如果找不到，降级为 "anonymous"（按匿名用户统一限流）
     *
     * 注意：编译时需要保留参数名（Spring Boot 默认开启 -parameters 编译选项）
     */
    private String extractUserId(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            if ("userId".equals(parameters[i].getName()) && args[i] != null) {
                return args[i].toString();
            }
        }
        return "anonymous";
    }
}
