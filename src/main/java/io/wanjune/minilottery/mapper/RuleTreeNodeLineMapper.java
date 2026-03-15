package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.RuleTreeNodeLine;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 规则树节点连线 Mapper（Phase 3 新增）
 */
@Mapper
public interface RuleTreeNodeLineMapper {

    /**
     * 查询指定规则树的所有连线
     */
    List<RuleTreeNodeLine> queryByTreeId(String treeId);
}
