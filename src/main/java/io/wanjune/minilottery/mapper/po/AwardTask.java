package io.wanjune.minilottery.mapper.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发奖任务表
 */
@Data
public class AwardTask {
    private Long id;
    /** 关联订单号 */
    private String orderId;
    /** 用户ID */
    private String userId;
    /** 奖品ID */
    private String awardId;
    /** 1-优惠券 2-实物 3-谢谢参与 */
    private Integer awardType;
    /** 0-待发送 1-发送中 2-已完成 3-失败 */
    private Integer status;
    /** 重试次数 */
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
