package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.Activity;
import org.apache.ibatis.annotations.Mapper;


/**
 * @author zjh
 * @since 2026/3/10 18:50
 */
@Mapper
public interface ActivityMapper {

    Activity queryByActivityId(String activityId);

    int deductStock(String activityId);

}