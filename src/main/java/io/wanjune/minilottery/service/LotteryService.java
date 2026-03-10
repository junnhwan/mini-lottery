package io.wanjune.minilottery.service;

import io.wanjune.minilottery.mapper.po.LotteryOrder;
import io.wanjune.minilottery.service.vo.DrawResultVO;

import java.util.List;

/**
 * 抽奖服务
 *
 * @author zjh
 * @since 2026/3/10 19:24
 */
public interface LotteryService {

    /**
     * 执行抽奖
     *
     * @param userId     用户ID
     * @param activityId 活动ID
     * @return 抽奖结果
     */
    DrawResultVO draw(String userId, String activityId);

    /**
     * 查询用户在某活动的抽奖记录
     *
     * @param userId     用户ID
     * @param activityId 活动ID
     * @return 抽奖订单列表
     */
    List<LotteryOrder> queryRecords(String userId, String activityId);
}
