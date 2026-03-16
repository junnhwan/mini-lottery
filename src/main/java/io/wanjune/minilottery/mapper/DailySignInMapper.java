package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.DailySignIn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 每日签到 Mapper
 *
 * @author zjh
 * @since 2026/3/16
 */
@Mapper
public interface DailySignInMapper {

    /**
     * 插入签到记录
     * 利用 UNIQUE(user_id, activity_id, sign_date) 做幂等
     * 重复签到会触发 DuplicateKeyException
     */
    void insert(DailySignIn dailySignIn);

    /**
     * 查询指定日期是否已签到（0=未签到，1=已签到）
     */
    int queryByDate(@Param("userId") String userId,
                    @Param("activityId") String activityId,
                    @Param("signDate") String signDate);
}
