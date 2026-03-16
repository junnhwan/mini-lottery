package io.wanjune.minilottery.mapper.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户积分账户
 *
 * 每个用户一个积分账户（UNIQUE on user_id）
 * - total_credit：累计获得积分（只增不减，用于统计）
 * - available_credit：可用余额（签到加、兑换减）
 *
 * @author zjh
 * @since 2026/3/16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreditAccount {
    private Long id;
    /** 用户ID */
    private String userId;
    /** 累计获得积分 */
    private Integer totalCredit;
    /** 可用积分余额 */
    private Integer availableCredit;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
