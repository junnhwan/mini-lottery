package io.wanjune.minilottery.mapper;

import io.wanjune.minilottery.mapper.po.RuleTreeNode;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 规则树节点 Mapper（Phase 3 新增）
 */
@Mapper
public interface RuleTreeNodeMapper {

    /**
     * 查询指定规则树的所有节点
     */
    List<RuleTreeNode> queryByTreeId(String treeId);
}
