package io.wanjune.minilottery.interceptor;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase 4 限流切面单元测试
 *
 * 直接实例化 RateLimitAspect，mock ProceedingJoinPoint 和注解
 * 不启动 Spring 容器，纯 Mockito 测试
 *
 * 测试覆盖：
 * 1. 正常请求放行
 * 2. 超频触发限流 → 调用 fallback
 * 3. 连续超频 → 触发拉黑
 * 4. 被拉黑后直接拒绝（不走令牌桶）
 * 5. 不同用户独立限流
 *
 * @author zjh
 * @since 2026/3/16
 */
@Slf4j
class RateLimitTest {

    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() throws Exception {
        aspect = new RateLimitAspect();
    }

    // ==================== 测试 1：正常请求放行 ====================

    /**
     * 首次请求，令牌桶有令牌 → 放行执行原方法
     */
    @Test
    void test_normalRequest_shouldProceed() throws Throwable {
        ProceedingJoinPoint jp = mockJoinPoint("user001");
        RateLimit annotation = mockAnnotation(1.0, 10, "fallback");
        when(jp.proceed()).thenReturn("OK");

        Object result = aspect.around(jp, annotation);

        assertEquals("OK", result);
        // 验证原方法被调用
        verify(jp).proceed();
    }

    // ==================== 测试 2：超频触发限流 ====================

    /**
     * 令牌桶耗尽后 → tryAcquire 失败 → 调用 fallback
     *
     * 面试点：permitsPerSecond = 0.001 意味着每 1000 秒才生成 1 个令牌
     * 第一次请求消耗预填的令牌，第二次请求立刻失败
     */
    @Test
    void test_exceedRate_shouldCallFallback() throws Throwable {
        ProceedingJoinPoint jp = mockJoinPoint("user002");
        RateLimit annotation = mockAnnotation(0.001, 0, "fallback");

        // 用极低的 permitsPerSecond，第一次消耗预填令牌
        when(jp.proceed()).thenReturn("OK");
        aspect.around(jp, annotation);

        // 第二次应该被限流 → 走 fallback
        ProceedingJoinPoint jp2 = mockJoinPoint("user002");
        Object result = aspect.around(jp2, annotation);

        assertEquals("FALLBACK", result);
    }

    // ==================== 测试 3：连续超频触发拉黑 ====================

    /**
     * 连续被限流超过 blacklistCount 次 → 用户被拉黑
     */
    @Test
    void test_continuousViolation_shouldBlacklist() throws Throwable {
        // blacklistCount = 3，连续超频 3 次后拉黑
        RateLimit annotation = mockAnnotation(0.001, 3, "fallback");

        // 第 1 次：正常放行（消耗预填令牌）
        ProceedingJoinPoint jp0 = mockJoinPoint("user003");
        when(jp0.proceed()).thenReturn("OK");
        aspect.around(jp0, annotation);

        // 第 2~4 次：被限流，累计违规 1、2、3 次
        for (int i = 0; i < 3; i++) {
            ProceedingJoinPoint jp = mockJoinPoint("user003");
            Object result = aspect.around(jp, annotation);
            assertEquals("FALLBACK", result);
        }

        // 检查黑名单缓存中的计数
        Cache<String, Long> blacklistCache = getBlacklistCache();
        Long count = blacklistCache.getIfPresent("user003");
        assertNotNull(count);
        assertEquals(3L, count);
    }

    // ==================== 测试 4：被拉黑后直接拒绝 ====================

    /**
     * 已被拉黑的用户 → 直接走 fallback，不消耗令牌桶资源
     */
    @Test
    void test_blacklisted_shouldRejectDirectly() throws Throwable {
        // 先手动写入黑名单计数 = 10（已超过阈值）
        Cache<String, Long> blacklistCache = getBlacklistCache();
        blacklistCache.put("user004", 10L);

        RateLimit annotation = mockAnnotation(1.0, 5, "fallback");
        ProceedingJoinPoint jp = mockJoinPoint("user004");

        Object result = aspect.around(jp, annotation);

        assertEquals("FALLBACK", result);
        // 验证原方法从未被调用（直接被拦截）
        verify(jp, never()).proceed();
    }

    // ==================== 测试 5：不同用户独立限流 ====================

    /**
     * userA 被限流不影响 userB 的令牌桶
     */
    @Test
    void test_differentUsers_shouldHaveIndependentLimits() throws Throwable {
        RateLimit annotation = mockAnnotation(0.001, 0, "fallback");

        // userA 消耗令牌
        ProceedingJoinPoint jpA1 = mockJoinPoint("userA");
        when(jpA1.proceed()).thenReturn("OK_A");
        assertEquals("OK_A", aspect.around(jpA1, annotation));

        // userA 第二次被限流
        ProceedingJoinPoint jpA2 = mockJoinPoint("userA");
        assertEquals("FALLBACK", aspect.around(jpA2, annotation));

        // userB 第一次请求应该正常放行（独立令牌桶）
        ProceedingJoinPoint jpB = mockJoinPoint("userB");
        when(jpB.proceed()).thenReturn("OK_B");
        assertEquals("OK_B", aspect.around(jpB, annotation));
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造 mock 的 ProceedingJoinPoint
     *
     * 模拟一个 Controller 方法调用：
     * - 参数名 "userId"，值为传入的 userId
     * - 目标对象是一个有 fallback 方法的 FakeController
     */
    private ProceedingJoinPoint mockJoinPoint(String userId) throws Exception {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);

        // 使用 FakeController.draw 方法的签名
        Method drawMethod = FakeController.class.getMethod("draw", String.class);
        Method fallbackMethod = FakeController.class.getMethod("fallback", String.class);

        when(jp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(drawMethod);
        when(signature.getParameterTypes()).thenReturn(drawMethod.getParameterTypes());
        when(jp.getArgs()).thenReturn(new Object[]{userId});

        // 目标对象是 FakeController 实例
        FakeController target = new FakeController();
        when(jp.getTarget()).thenReturn(target);

        return jp;
    }

    /**
     * 构造 mock 的 @RateLimit 注解
     */
    private RateLimit mockAnnotation(double permitsPerSecond, int blacklistCount, String fallbackMethod) {
        RateLimit annotation = mock(RateLimit.class);
        when(annotation.key()).thenReturn("userId");
        when(annotation.permitsPerSecond()).thenReturn(permitsPerSecond);
        when(annotation.blacklistCount()).thenReturn(blacklistCount);
        when(annotation.fallbackMethod()).thenReturn(fallbackMethod);
        return annotation;
    }

    /**
     * 通过反射获取 aspect 内部的 blacklistCache（用于验证拉黑计数）
     */
    @SuppressWarnings("unchecked")
    private Cache<String, Long> getBlacklistCache() throws Exception {
        Field field = RateLimitAspect.class.getDeclaredField("blacklistCache");
        field.setAccessible(true);
        return (Cache<String, Long>) field.get(aspect);
    }

    // ==================== 测试用的 Fake Controller ====================

    /**
     * 模拟 Controller，提供 draw 和 fallback 方法
     * fallback 方法签名必须与 draw 一致（反射查找要求）
     */
    public static class FakeController {
        public Object draw(String userId) {
            return "OK";
        }

        public Object fallback(String userId) {
            return "FALLBACK";
        }
    }
}
