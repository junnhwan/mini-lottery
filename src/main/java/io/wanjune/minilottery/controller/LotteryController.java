package io.wanjune.minilottery.controller;

import io.wanjune.minilottery.mapper.po.LotteryOrder;
import io.wanjune.minilottery.service.LotteryService;
import io.wanjune.minilottery.service.vo.DrawResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
     * 执行抽奖
     */
    @GetMapping("/draw")
    public DrawResultVO draw(@RequestParam String userId, @RequestParam String activityId) {
        return lotteryService.draw(userId, activityId);
    }

    /**
     * 查询抽奖记录
     */
    @GetMapping("/records")
    public List<LotteryOrder> records(@RequestParam String userId, @RequestParam String activityId) {
        return lotteryService.queryRecords(userId, activityId);
    }
}
