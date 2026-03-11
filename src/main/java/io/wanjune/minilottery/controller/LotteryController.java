package io.wanjune.minilottery.controller;

import io.wanjune.minilottery.common.Result;
import io.wanjune.minilottery.interceptor.RateLimit;
import io.wanjune.minilottery.mapper.po.LotteryOrder;
import io.wanjune.minilottery.service.LotteryService;
import io.wanjune.minilottery.service.vo.DrawResultVO;
import lombok.RequiredArgsConstructor;
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
public class LotteryController {

    private final LotteryService lotteryService;

    /**
     * 执行抽奖（每用户 60 秒内最多 5 次）
     *
     * POST：抽奖有副作用（扣库存、写订单），GET 不应有副作用
     */
    @RateLimit(permits = 5, window = 60)
    @PostMapping("/draw")
    public Result<DrawResultVO> draw(@RequestParam String userId, @RequestParam String activityId) {
        return Result.success(lotteryService.draw(userId, activityId));
    }

    /**
     * 查询抽奖记录
     */
    @GetMapping("/records")
    public Result<List<LotteryOrder>> records(@RequestParam String userId, @RequestParam String activityId) {
        return Result.success(lotteryService.queryRecords(userId, activityId));
    }
}
