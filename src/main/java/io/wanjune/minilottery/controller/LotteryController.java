package io.wanjune.minilottery.controller;

import io.wanjune.minilottery.common.Result;
import io.wanjune.minilottery.interceptor.RateLimit;
import io.wanjune.minilottery.mapper.po.LotteryOrder;
import io.wanjune.minilottery.service.LotteryService;
import io.wanjune.minilottery.service.vo.DrawResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 抽奖接口
 *
 * @author zjh
 * @since 2026/3/10
 */
@RestController
@RequestMapping("/api/lottery")
@RequiredArgsConstructor
@Slf4j
public class LotteryController {

    private final LotteryService lotteryService;

    /**
     * 执行抽奖
     *
     * Phase 4 改造：@RateLimit 从 Redis ZSET 滑动窗口 → Guava 令牌桶
     * - permitsPerSecond = 1.0：每秒 1 个令牌（允许短时突发）
     * - blacklistCount = 10：连续被限流 10 次后拉黑 24 小时
     * - fallbackMethod：被限流/拉黑时调用兜底方法，返回友好提示
     *
     * POST：抽奖有副作用（扣库存、写订单），GET 不应有副作用
     */
    @RateLimit(key = "userId", permitsPerSecond = 1.0, blacklistCount = 10, fallbackMethod = "drawRateLimitFallback")
    @PostMapping("/draw")
    public Result<DrawResultVO> draw(@RequestParam String userId, @RequestParam String activityId) {
        return Result.success(lotteryService.draw(userId, activityId));
    }

    /**
     * 限流兜底方法 — 用户超频或被拉黑时返回
     *
     * 方法签名必须和 draw() 完全一致（参数类型 + 返回类型），
     * 因为 AOP 通过反射用 getMethod(name, parameterTypes) 查找
     *
     * 面试点：为什么不直接抛异常？
     * → 异常返回给前端是 error 状态，触发前端错误处理逻辑
     * → fallback 返回正常 JSON 格式 + 错误码，前端可以友好提示"请稍后再试"
     */
    public Result<DrawResultVO> drawRateLimitFallback(@RequestParam String userId, @RequestParam String activityId) {
        log.warn("抽奖限流兜底 userId={}, activityId={}", userId, activityId);
        return Result.fail(1006, "请求过于频繁，请稍后再试");
    }

    /**
     * 查询抽奖记录
     */
    @GetMapping("/records")
    public Result<List<LotteryOrder>> records(@RequestParam String userId, @RequestParam String activityId) {
        return Result.success(lotteryService.queryRecords(userId, activityId));
    }
}
