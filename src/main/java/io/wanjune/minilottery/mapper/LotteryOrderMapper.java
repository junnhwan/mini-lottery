package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.LotteryOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author zjh
 * @since 2026/3/10 18:52
 */
@Mapper
public interface LotteryOrderMapper {

    void insert(LotteryOrder lotteryOrder);

    List<LotteryOrder> queryByUserIdAndActivityId(@Param("userId")String userId, @Param("activityId")String activityId);

}