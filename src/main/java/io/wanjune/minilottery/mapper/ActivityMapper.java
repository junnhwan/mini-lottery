package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;


/**
 * @author zjh
 * @since 2026/3/10 18:50
 */
@Mapper
public interface ActivityMapper {

    Activity queryByActivityId(String activityId);

    int deductStock(String activityId);

    /** 回滚库存（超时订单用） */
    int rollbackStock(String activityId);

    /** 对账补偿：将 DB 库存修正为 Redis 快照 */
    int syncRemainStock(@Param("activityId") String activityId,
                        @Param("remainStock") int remainStock);

    /** 按状态查询活动列表（装配策略时用，查所有进行中的活动） */
    List<Activity> queryByStatus(int status);

}
