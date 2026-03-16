package io.wanjune.minilottery.controller;

import io.wanjune.minilottery.common.Result;
import io.wanjune.minilottery.mapper.po.UserCreditAccount;
import io.wanjune.minilottery.service.UserBehaviorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户行为接口 — 签到返利 + 积分兑换
 *
 * Phase 5 新增：
 * - POST /api/user/sign-in      每日签到（+10 积分 + MQ → +1 抽奖机会）
 * - POST /api/user/credit-exchange  积分兑换抽奖机会（-N 积分 + MQ → +1 抽奖机会）
 * - GET  /api/user/credit        查询积分余额
 *
 * @author zjh
 * @since 2026/3/16
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserBehaviorController {

    private final UserBehaviorService userBehaviorService;

    /**
     * 每日签到
     *
     * 每天每活动只能签一次（UNIQUE 索引幂等）
     * 签到奖励：+10 积分 + MQ 异步增加 1 次抽奖机会
     */
    @PostMapping("/sign-in")
    public Result<Void> signIn(@RequestParam String userId, @RequestParam String activityId) {
        userBehaviorService.signIn(userId, activityId);
        return Result.success(null);
    }

    /**
     * 积分兑换抽奖机会
     *
     * 消耗指定积分，换取 1 次额外抽奖机会
     * 积分不足时返回错误
     */
    @PostMapping("/credit-exchange")
    public Result<Void> creditExchange(@RequestParam String userId,
                                       @RequestParam String activityId,
                                       @RequestParam int cost) {
        userBehaviorService.creditExchange(userId, activityId, cost);
        return Result.success(null);
    }

    /**
     * 查询积分余额
     */
    @GetMapping("/credit")
    public Result<UserCreditAccount> queryCredit(@RequestParam String userId) {
        UserCreditAccount account = userBehaviorService.queryCredit(userId);
        return Result.success(account);
    }
}
