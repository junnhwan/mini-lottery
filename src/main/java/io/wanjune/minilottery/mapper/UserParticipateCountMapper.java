package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.UserParticipateCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * @author zjh
 * @since 2026/3/10 18:52
 */
@Mapper
public interface UserParticipateCountMapper {

    int queryByUserIdAndActivityId(@Param("userId")String userId, @Param("activityId")String activityId);

    void insertOrUpdate(UserParticipateCount userParticipateCount);

}