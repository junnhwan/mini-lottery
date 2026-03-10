package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.AwardTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 发奖任务 Mapper
 *
 * @author zjh
 * @since 2026/3/10
 */
@Mapper
public interface AwardTaskMapper {

    /** 插入发奖任务 */
    void insert(AwardTask awardTask);

    /** 根据订单号查询任务 */
    AwardTask queryByOrderId(@Param("orderId") String orderId);

    /** 更新任务状态 */
    int updateStatus(@Param("orderId") String orderId, @Param("status") int status);

    /** 增加重试次数 */
    int incrementRetryCount(@Param("orderId") String orderId);
}
