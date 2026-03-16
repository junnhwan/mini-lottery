package io.wanjune.minilottery.mapper.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日签到记录
 *
 * 幂等设计：UNIQUE(user_id, activity_id, sign_date)
 * 同一用户同一活动同一天只能签到一次，重复插入会触发 DuplicateKeyException
 *
 * @author zjh
 * @since 2026/3/16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailySignIn {
    private Long id;
    /** 用户ID */
    private String userId;
    /** 活动ID */
    private String activityId;
    /** 签到日期（DATE 类型，不含时分秒） */
    private LocalDate signDate;
    private LocalDateTime createTime;
}
