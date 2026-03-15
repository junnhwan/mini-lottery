package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.StrategyRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 策略规则 Mapper（Phase 3 新增）
 */
@Mapper
public interface StrategyRuleMapper {

    /**
     * 查询指定活动的指定规则配置
     */
    StrategyRule queryByActivityIdAndRuleModel(@Param("activityId") String activityId,
                                               @Param("ruleModel") String ruleModel);
}
