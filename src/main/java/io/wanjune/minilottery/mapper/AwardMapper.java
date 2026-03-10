package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.Award;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @author zjh
 * @since 2026/3/10 18:52
 */
@Mapper
public interface AwardMapper {

    List<Award> queryByActivityId(String activityId);

}