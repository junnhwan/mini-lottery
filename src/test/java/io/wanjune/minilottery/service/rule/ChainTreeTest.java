package io.wanjune.minilottery.service.rule;

import io.wanjune.minilottery.mapper.UserParticipateCountMapper;
import io.wanjune.minilottery.mapper.po.UserParticipateCount;
import io.wanjune.minilottery.service.armory.StrategyArmory;
import io.wanjune.minilottery.service.rule.chain.ChainFactory;
import io.wanjune.minilottery.service.rule.tree.TreeFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 集成测试：责任链 + 规则树（需要 MySQL + Redis 环境）
 *
 * 测试前请确保已执行 Phase 3 的 DDL + 测试数据（sql/init.sql）
 *
 * 测试活动 A20260310001 配置：
 * - 责任链：rule_blacklist → rule_weight → rule_default
 * - 黑名单：R004:user_black_001,user_black_002
 * - 权重：2:R001,R002,R003,R004  3:R001,R002,R004
 * - 规则树 tree_lock_1：rule_lock(阈值2) → rule_stock → rule_luck_award(R004)
 * - 奖品 R001/R002 关联 tree_lock_1
 *
 * @author zjh
 * @since 2026/3/15
 */
@Slf4j
@SpringBootTest
public class ChainTreeTest {

    @Autowired private ChainFactory chainFactory;
    @Autowired private TreeFactory treeFactory;
    @Autowired private StrategyArmory strategyArmory;
    @Autowired private UserParticipateCountMapper userParticipateCountMapper;
    @Autowired private RedissonClient redissonClient;

    private static final String ACTIVITY_ID = "A20260310001";

    /**
     * 每个测试前装配概率表 + 权重子奖池 + 奖品库存预热
     */
    @BeforeEach
    void setUp() {
        strategyArmory.armory(ACTIVITY_ID);
    }

    // ==================== 责任链测试 ====================

    /**
     * 黑名单用户 → BlackList 节点直接拦截，返回兜底奖品 R004
     *
     * 面试追问：黑名单为什么放在链的最前面？
     * → 最优先拦截，避免黑名单用户消耗系统资源（后续的概率计算、库存扣减等都不需要执行）
     */
    @Test
    void test_chain_blacklist_shouldReturnFallback() {
        ChainFactory.ChainResult result = chainFactory.openLogicChain(ACTIVITY_ID)
                .logic("user_black_001", ACTIVITY_ID);

        log.info("黑名单测试: logicModel={}, awardId={}", result.logicModel(), result.awardId());
        assertEquals(ChainFactory.LogicModel.RULE_BLACKLIST, result.logicModel());
        assertEquals("R004", result.awardId());
    }

    /**
     * 新用户（0 次参与）→ 不匹配权重阈值 → 走 Default 全量奖池抽奖
     */
    @Test
    void test_chain_default_newUser() {
        ChainFactory.ChainResult result = chainFactory.openLogicChain(ACTIVITY_ID)
                .logic("test_default_" + System.currentTimeMillis(), ACTIVITY_ID);

        log.info("默认链测试: logicModel={}, awardId={}", result.logicModel(), result.awardId());
        assertEquals(ChainFactory.LogicModel.RULE_DEFAULT, result.logicModel());
        assertNotNull(result.awardId());
        // 返回的奖品应该是活动 A20260310001 的四个奖品之一
        assertTrue(Set.of("R001", "R002", "R003", "R004").contains(result.awardId()));
    }

    /**
     * 参与 >= 2 次的用户 → 匹配权重阈值 2 → 从权重子奖池抽奖
     *
     * 面试追问：权重子奖池怎么实现的？
     * → armory 时为每个权重阈值构建独立的概率查找表，key = activityId_threshold
     */
    @Test
    void test_chain_weight_highParticipation() {
        String testUser = "test_weight_" + System.currentTimeMillis();
        // 插入 2 次参与记录 → participate_count = 2 → 匹配权重阈值 2
        insertParticipateCount(testUser, 2);

        ChainFactory.ChainResult result = chainFactory.openLogicChain(ACTIVITY_ID)
                .logic(testUser, ACTIVITY_ID);

        log.info("权重链测试: logicModel={}, awardId={}", result.logicModel(), result.awardId());
        assertEquals(ChainFactory.LogicModel.RULE_WEIGHT, result.logicModel());
        assertNotNull(result.awardId());
        // 权重阈值 2 的子奖池包含 R001,R002,R003,R004
        assertTrue(Set.of("R001", "R002", "R003", "R004").contains(result.awardId()));
    }

    // ==================== 规则树测试 ====================

    /**
     * rule_lock 拦截：新用户参与 0 次 < 阈值 2 → TAKE_OVER → 兜底 R004
     *
     * 面试追问：rule_lock 的作用？
     * → 保护高价值奖品，只有活跃用户（参与次数达标）才有资格获得
     */
    @Test
    void test_tree_lock_block_newUser() {
        String testUser = "test_lock_block_" + System.currentTimeMillis();
        // 0 次参与 < 阈值 2 → rule_lock 拦截
        String result = treeFactory.process("tree_lock_1", testUser, ACTIVITY_ID, "R001");

        log.info("rule_lock 拦截: R001 → {}", result);
        assertEquals("R004", result);
    }

    /**
     * rule_lock 放行 + rule_stock 扣减成功 → 确认发放原奖品
     *
     * 面试追问：per-award 库存和 activity 库存的区别？
     * → activity 库存控制总参与名额（Phase 2 DECR），per-award 库存控制单个奖品数量上限
     */
    @Test
    void test_tree_stock_deduct_success() {
        String testUser = "test_stock_ok_" + System.currentTimeMillis();
        // 参与 2 次 → rule_lock 放行
        insertParticipateCount(testUser, 2);
        // 设置 R001 奖品库存 = 10（充足）
        redissonClient.getAtomicLong("award_stock:" + ACTIVITY_ID + "_R001").set(10);

        String result = treeFactory.process("tree_lock_1", testUser, ACTIVITY_ID, "R001");

        log.info("库存扣减成功: R001 → {}", result);
        assertEquals("R001", result);  // 库存充足 → 确认原奖品
    }

    /**
     * rule_lock 放行 + rule_stock 库存为 0 → 走兜底 R004
     *
     * 面试追问：库存为 0 时 DECR 返回 -1 怎么处理？
     * → 检测 surplus < 0，重置为 0（SET 0），返回 ALLOW → 走兜底奖品
     */
    @Test
    void test_tree_stock_empty_shouldFallback() {
        String testUser = "test_stock_empty_" + System.currentTimeMillis();
        // 参与 2 次 → rule_lock 放行
        insertParticipateCount(testUser, 2);
        // 设置 R002 奖品库存 = 0（已耗尽）
        redissonClient.getAtomicLong("award_stock:" + ACTIVITY_ID + "_R002").set(0);

        String result = treeFactory.process("tree_lock_1", testUser, ACTIVITY_ID, "R002");

        log.info("库存不足兜底: R002 → {}", result);
        assertEquals("R004", result);  // 库存不足 → 兜底
    }

    /**
     * 完整链路：责任链 → 规则树（端到端验证）
     *
     * 新用户走 Default → 如果抽到有规则树的奖品 → rule_lock 拦截 → 兜底
     * 验证链和树可以串联工作
     */
    @Test
    void test_full_chain_then_tree() {
        String testUser = "test_full_" + System.currentTimeMillis();

        // Step 1: 责任链
        ChainFactory.ChainResult chainResult = chainFactory.openLogicChain(ACTIVITY_ID)
                .logic(testUser, ACTIVITY_ID);
        log.info("责任链结果: logicModel={}, awardId={}", chainResult.logicModel(), chainResult.awardId());

        String awardId = chainResult.awardId();

        // Step 2: 如果抽到 R001 或 R002（配置了 tree_lock_1），进入规则树
        if ("R001".equals(awardId) || "R002".equals(awardId)) {
            String treeResult = treeFactory.process("tree_lock_1", testUser, ACTIVITY_ID, awardId);
            log.info("规则树决策: {} → {}", awardId, treeResult);
            // 新用户参与 0 次 → rule_lock 拦截 → 兜底 R004
            assertEquals("R004", treeResult);
        } else {
            // R003/R004 没有规则树配置，直接确认
            log.info("奖品 {} 无规则树配置，直接确认", awardId);
            assertTrue(Set.of("R003", "R004").contains(awardId));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 插入用户参与次数记录
     * insertOrUpdate 使用 ON DUPLICATE KEY UPDATE participate_count = participate_count + 1
     * 首次 INSERT → count=1，后续 UPDATE → count+1
     */
    private void insertParticipateCount(String userId, int count) {
        UserParticipateCount pc = UserParticipateCount.builder()
                .userId(userId)
                .activityId(ACTIVITY_ID)
                .build();
        for (int i = 0; i < count; i++) {
            userParticipateCountMapper.insertOrUpdate(pc);
        }
    }
}
