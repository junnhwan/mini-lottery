package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.RuleTree;
import org.apache.ibatis.annotations.Mapper;

/**
 * 规则树 Mapper（Phase 3 新增）
 */
@Mapper
public interface RuleTreeMapper {

    /**
     * 根据 treeId 查询规则树定义
     */
    RuleTree queryByTreeId(String treeId);
}
