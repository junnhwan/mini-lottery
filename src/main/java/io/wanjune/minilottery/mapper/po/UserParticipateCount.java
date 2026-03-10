package io.wanjune.minilottery.mapper.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户参与次数表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserParticipateCount {
    private Long id;
    /** 用户ID */
    private String userId;
    /** 活动ID */
    private String activityId;
    /** 已参与次数 */
    private Integer participateCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
