package io.wanjune.minilottery.mapper.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 抽奖订单表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LotteryOrder {
    private Long id;
    /** 订单号 */
    private String orderId;
    /** 用户ID */
    private String userId;
    /** 活动ID */
    private String activityId;
    /** 中奖奖品ID */
    private String awardId;
    /** 中奖奖品名称 */
    private String awardName;
    /** 0-待处理 1-已完成 2-已超时取消 */
    private Integer status;
    /** 订单超时时间（延迟队列用） */
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
