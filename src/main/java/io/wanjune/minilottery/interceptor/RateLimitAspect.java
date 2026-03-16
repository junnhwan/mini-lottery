package io.wanjune.minilottery.interceptor;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.TimeUnit;

/**
 * 限流切面 — Guava 令牌桶 + 超频自动拉黑 + 反射调用 fallback
 *
 * 改造前（Phase 1 / Week 3）：
 *   Redis ZSET 滑动窗口 → ZREMRANGEBYSCORE + ZCARD + ZADD（3 次网络调用，非原子）
 *
 * 改造后（Phase 4）：
 *   Guava RateLimiter 令牌桶（JVM 本地，微秒级）+ Guava Cache 拉黑计数器
 *
 * 核心组件：
 * 1. rateLimiterCache — 每用户一个令牌桶，1 分钟不活跃自动清理
 * 2. blacklistCache  — 拉黑计数器，超频 N 次后标记为黑名单，24 小时自动解封
 *
 * 面试追问：为什么用 Guava 本地方案而非 Redis 分布式？
 * → 单实例部署下性能更高（微秒 vs 毫秒），无网络开销，无原子性问题
 * → 多实例部署可在 Nginx 层再加一道 limit_req，形成两级限流
 *
 * @author zjh
 * @since 2026/3/10
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    /**
     * 每用户独立的令牌桶缓存
     * key = userId，value = 该用户的 RateLimiter 实例
     * expireAfterAccess(1min)：用户 1 分钟内没有新请求就清理掉，释放内存
     *
     * 面试点：为什么不用 ConcurrentHashMap？
     * → Guava Cache 自带过期淘汰，ConcurrentHashMap 需要自己写定时清理
     */
    private final Cache<String, RateLimiter> rateLimiterCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .build();

    /**
     * 超频拉黑计数器
     * key = userId，value = 连续被限流的次数
     * expireAfterWrite(24h)：拉黑后 24 小时自动解封
     *
     * 面试点：为什么是 expireAfterWrite 而不是 expireAfterAccess？
     * → Write：从首次被限流开始计时 24 小时，期间无论访问多少次都不会重置
     * → Access：每次被拒绝都会刷新过期时间，导致永远不解封
     */
    private final Cache<String, Long> blacklistCache = CacheBuilder.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();

    /**
     * 环绕通知：拦截所有带 @RateLimit 注解的方法
     *
     * 执行流程：
     * 1. 提取 userId → 2. 检查黑名单 → 3. 令牌桶判断 → 4. 放行或调 fallback
     */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {

        // --- 1. 提取限流标识（userId） ---
        String userId = extractKeyValue(joinPoint, rateLimit.key());

        // --- 2. 黑名单检查（短路优化：被拉黑直接拒绝，不消耗令牌桶资源） ---
        if (rateLimit.blacklistCount() > 0) {
            Long violationCount = blacklistCache.getIfPresent(userId);
            if (violationCount != null && violationCount >= rateLimit.blacklistCount()) {
                log.warn("用户已被拉黑 userId={}, 违规次数={}, 阈值={}",
                        userId, violationCount, rateLimit.blacklistCount());
                return invokeFallback(joinPoint, rateLimit.fallbackMethod());
            }
        }

        // --- 3. 令牌桶检查 ---
        // getIfPresent + put 代替 get(callable)，避免 ExecutionException 包装
        RateLimiter rateLimiter = rateLimiterCache.getIfPresent(userId);
        if (rateLimiter == null) {
            rateLimiter = RateLimiter.create(rateLimit.permitsPerSecond());
            rateLimiterCache.put(userId, rateLimiter);
        }

        // tryAcquire()：非阻塞尝试获取令牌
        // 面试点：为什么用 tryAcquire 而不是 acquire？
        // → acquire 会阻塞等待令牌（线程挂起），tryAcquire 立即返回，不影响 Tomcat 线程池
        if (rateLimiter.tryAcquire()) {
            // --- 4a. 获取令牌成功 → 放行 ---
            return joinPoint.proceed();
        }

        // --- 4b. 获取令牌失败 → 累加拉黑计数 + 调用 fallback ---
        if (rateLimit.blacklistCount() > 0) {
            Long current = blacklistCache.getIfPresent(userId);
            long newCount = (current == null) ? 1L : current + 1L;
            blacklistCache.put(userId, newCount);
            log.warn("限流触发 userId={}, 累计违规={}/{}", userId, newCount, rateLimit.blacklistCount());
        } else {
            log.warn("限流触发 userId={}", userId);
        }

        return invokeFallback(joinPoint, rateLimit.fallbackMethod());
    }

    /**
     * 通过反射调用 fallback 方法
     *
     * 要求 fallback 方法与原方法在同一个类中，且参数签名完全一致
     * 通过 getMethod(name, parameterTypes) 查找 → invoke(target, args)
     *
     * 面试点：为什么用反射而不是接口回调？
     * → 注解驱动更简洁，开发者只需在 Controller 中写一个同签名方法
     * → 接口回调需要 Controller 实现特定接口，侵入性强
     */
    private Object invokeFallback(ProceedingJoinPoint joinPoint, String fallbackMethod) throws Exception {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = joinPoint.getTarget().getClass()
                .getMethod(fallbackMethod, signature.getParameterTypes());
        return method.invoke(joinPoint.getTarget(), joinPoint.getArgs());
    }

    /**
     * 从方法参数中按名称提取限流 key 值
     *
     * 遍历方法参数，找到名称匹配 key 的参数并返回其值
     * 注意：需要编译时保留参数名（Spring Boot 默认开启 -parameters）
     */
    private String extractKeyValue(ProceedingJoinPoint joinPoint, String key) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            if (key.equals(parameters[i].getName()) && args[i] != null) {
                return args[i].toString();
            }
        }
        return "anonymous";
    }
}
