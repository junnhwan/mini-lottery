package io.wanjune.minilottery.mapper.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动表
 */
@Data
public class Activity {
    private Long id;
    /** 活动ID */
    private String activityId;
    /** 活动名称 */
    private String activityName;
    /** 0-待开始 1-进行中 2-已结束 */
    private Integer status;
    /** 总库存 */
    private Integer totalStock;
    /** 剩余库存 */
    private Integer remainStock;
    /** 每人最多参与次数 */
    private Integer maxPerUser;
    /** 活动开始时间 */
    private LocalDateTime beginTime;
    /** 活动结束时间 */
    private LocalDateTime endTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
