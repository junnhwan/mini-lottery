package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.LotteryOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author zjh
 * @since 2026/3/10 18:52
 */
@Mapper
public interface LotteryOrderMapper {

    void insert(LotteryOrder lotteryOrder);

    List<LotteryOrder> queryByUserIdAndActivityId(@Param("userId")String userId, @Param("activityId")String activityId);

    /** 根据订单号查询 */
    LotteryOrder queryByOrderId(@Param("orderId") String orderId);

    /** 更新订单状态 */
    int updateStatus(@Param("orderId") String orderId, @Param("status") int status);

    /** 查询长时间未推进的待处理订单 */
    List<LotteryOrder> queryStalePendingOrders(@Param("status") int status,
                                               @Param("beforeTime") LocalDateTime beforeTime,
                                               @Param("now") LocalDateTime now,
                                               @Param("limit") int limit);

}
