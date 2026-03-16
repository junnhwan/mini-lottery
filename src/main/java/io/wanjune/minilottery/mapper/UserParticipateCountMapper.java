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

    /**
     * 查询用户的额外抽奖机会（签到/兑换获得）
     * 返回 0 如果记录不存在
     */
    int queryBonusCount(@Param("userId") String userId, @Param("activityId") String activityId);

    /**
     * 增加额外抽奖机会（RebateConsumer 调用）
     * 如果记录不存在则 INSERT（participate_count=0, bonus_count=rewardCount）
     */
    void addBonusCount(@Param("userId") String userId,
                       @Param("activityId") String activityId,
                       @Param("rewardCount") int rewardCount);

}