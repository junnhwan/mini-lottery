package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.UserCreditAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户积分账户 Mapper
 *
 * @author zjh
 * @since 2026/3/16
 */
@Mapper
public interface UserCreditAccountMapper {

    /**
     * 新增或更新积分（ON DUPLICATE KEY UPDATE）
     * 首次签到时 INSERT，后续签到 UPDATE 累加
     */
    void insertOrAddCredit(@Param("userId") String userId, @Param("amount") int amount);

    /**
     * 扣减积分
     * WHERE available_credit >= cost 防止扣成负数（乐观锁思路）
     *
     * @return 影响行数，0 表示余额不足
     */
    int deductCredit(@Param("userId") String userId, @Param("cost") int cost);

    /**
     * 查询用户积分账户
     */
    UserCreditAccount queryByUserId(@Param("userId") String userId);
}
