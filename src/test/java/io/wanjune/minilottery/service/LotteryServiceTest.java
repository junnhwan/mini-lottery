package io.wanjune.minilottery.service;

import io.wanjune.minilottery.cache.MultiLevelCacheService;
import io.wanjune.minilottery.common.BusinessException;
import io.wanjune.minilottery.lock.StockService;
import io.wanjune.minilottery.mapper.AwardTaskMapper;
import io.wanjune.minilottery.mapper.LotteryOrderMapper;
import io.wanjune.minilottery.mapper.UserParticipateCountMapper;
import io.wanjune.minilottery.mapper.po.Activity;
import io.wanjune.minilottery.mapper.po.Award;
import io.wanjune.minilottery.mq.producer.MQProducer;
import io.wanjune.minilottery.service.armory.StrategyArmory;
import io.wanjune.minilottery.service.impl.LotteryServiceImpl;
import io.wanjune.minilottery.service.rule.chain.ChainFactory;
import io.wanjune.minilottery.service.rule.chain.ILogicChain;
import io.wanjune.minilottery.service.rule.tree.TreeFactory;
import io.wanjune.minilottery.service.vo.DrawResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 抽奖服务单元测试（Mockito，不启动 Spring 容器）
 *
 * 测试各种异常场景下 BusinessException 是否正确抛出
 *
 * Phase 3 改造：draw() 流程改为 ChainFactory（责任链）+ TreeFactory（规则树）
 * 成功场景中 MQ 发送在 TransactionSynchronization.afterCommit() 中，
 * 单元测试无真实事务所以 afterCommit 不会触发，MQ 验证由集成测试覆盖
 *
 * @author zjh
 * @since 2026/3/11
 */
@ExtendWith(MockitoExtension.class)
class LotteryServiceTest {

    @InjectMocks
    private LotteryServiceImpl lotteryService;

    @Mock private MultiLevelCacheService cacheService;
    @Mock private UserParticipateCountMapper userParticipateCountMapper;
    @Mock private StockService stockService;
    @Mock private StrategyArmory strategyArmory;
    @Mock private LotteryOrderMapper lotteryOrderMapper;
    @Mock private AwardTaskMapper awardTaskMapper;
    @Mock private MQProducer mqProducer;
    @Mock private ChainFactory chainFactory;
    @Mock private TreeFactory treeFactory;

    /**
     * 初始化事务同步管理器
     * LotteryServiceImpl.draw() 中使用了 TransactionSynchronizationManager.registerSynchronization()
     * Mockito 测试没有真实事务，需要手动初始化，否则 registerSynchronization 会抛 IllegalStateException
     */
    @BeforeEach
    void setUp() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ========== 异常场景 ==========

    @Test
    void draw_activityNotFound_shouldThrow1001() {
        when(cacheService.getActivity("NOT_EXIST")).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> lotteryService.draw("user001", "NOT_EXIST"));
        assertEquals(1001, e.getCode());
    }

    @Test
    void draw_activityNotActive_shouldThrow1002() {
        Activity activity = buildActivity();
        activity.setStatus(0);  // 未开始
        when(cacheService.getActivity("A001")).thenReturn(activity);

        BusinessException e = assertThrows(BusinessException.class,
                () -> lotteryService.draw("user001", "A001"));
        assertEquals(1002, e.getCode());
    }

    @Test
    void draw_activityExpired_shouldThrow1003() {
        Activity activity = buildActivity();
        activity.setBeginTime(LocalDateTime.now().minusDays(10));
        activity.setEndTime(LocalDateTime.now().minusDays(1));  // 已过期
        when(cacheService.getActivity("A001")).thenReturn(activity);

        BusinessException e = assertThrows(BusinessException.class,
                () -> lotteryService.draw("user001", "A001"));
        assertEquals(1003, e.getCode());
    }

    @Test
    void draw_exceedMaxParticipation_shouldThrow1004() {
        Activity activity = buildActivity();
        when(cacheService.getActivity("A001")).thenReturn(activity);
        when(userParticipateCountMapper.queryByUserIdAndActivityId("user001", "A001")).thenReturn(3);

        BusinessException e = assertThrows(BusinessException.class,
                () -> lotteryService.draw("user001", "A001"));
        assertEquals(1004, e.getCode());
    }

    @Test
    void draw_stockNotEnough_shouldThrow1005() {
        Activity activity = buildActivity();
        when(cacheService.getActivity("A001")).thenReturn(activity);
        when(userParticipateCountMapper.queryByUserIdAndActivityId("user001", "A001")).thenReturn(0);
        // Phase 2：deductStock 签名改为 (String, LocalDateTime)
        when(stockService.deductStock(eq("A001"), any(LocalDateTime.class))).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> lotteryService.draw("user001", "A001"));
        assertEquals(1005, e.getCode());
    }

    // ========== 正常场景 ==========

    @Test
    void draw_success_shouldReturnResult() {
        Activity activity = buildActivity();
        when(cacheService.getActivity("A001")).thenReturn(activity);
        when(userParticipateCountMapper.queryByUserIdAndActivityId("user001", "A001")).thenReturn(0);
        // Phase 2：deductStock 签名改为 (String, LocalDateTime)
        when(stockService.deductStock(eq("A001"), any(LocalDateTime.class))).thenReturn(true);

        // Phase 3：draw() 改为调用 ChainFactory（责任链）+ TreeFactory（规则树）
        // mock 责任链：openLogicChain → 返回 mock 链节点 → logic() 返回 RULE_DEFAULT 结果
        ILogicChain mockChain = mock(ILogicChain.class);
        when(chainFactory.openLogicChain("A001")).thenReturn(mockChain);
        when(mockChain.logic("user001", "A001")).thenReturn(
                new ChainFactory.ChainResult("AWARD_001", ChainFactory.LogicModel.RULE_DEFAULT));

        // 奖品数据（ruleModels=null → 不走规则树）
        Award award = new Award();
        award.setAwardId("AWARD_001");
        award.setAwardName("优惠券");
        award.setAwardType(1);
        award.setAwardRate(new BigDecimal("0.50"));
        when(cacheService.getAwards("A001")).thenReturn(List.of(award));

        DrawResultVO result = lotteryService.draw("user001", "A001");

        assertNotNull(result);
        assertEquals("AWARD_001", result.getAwardId());
        assertEquals("优惠券", result.getAwardName());

        // 验证关键 DB 写入被调用
        verify(lotteryOrderMapper).insert(any());
        verify(awardTaskMapper).insert(any());
        // 验证责任链被调用
        verify(chainFactory).openLogicChain("A001");
        verify(mockChain).logic("user001", "A001");
        // 注意：MQ 发送在 afterCommit() 中，单元测试无真实事务不会触发
    }

    @Test
    void draw_success_withRuleTree_shouldReturnTreeResult() {
        Activity activity = buildActivity();
        when(cacheService.getActivity("A001")).thenReturn(activity);
        when(userParticipateCountMapper.queryByUserIdAndActivityId("user001", "A001")).thenReturn(0);
        when(stockService.deductStock(eq("A001"), any(LocalDateTime.class))).thenReturn(true);

        // 责任链返回 RULE_DEFAULT + 初始奖品 AWARD_001
        ILogicChain mockChain = mock(ILogicChain.class);
        when(chainFactory.openLogicChain("A001")).thenReturn(mockChain);
        when(mockChain.logic("user001", "A001")).thenReturn(
                new ChainFactory.ChainResult("AWARD_001", ChainFactory.LogicModel.RULE_DEFAULT));

        // 奖品带规则树配置（ruleModels="tree_lock_1" → 需要走规则树决策）
        Award award001 = new Award();
        award001.setAwardId("AWARD_001");
        award001.setAwardName("一等奖");
        award001.setAwardType(2);
        award001.setAwardRate(new BigDecimal("0.01"));
        award001.setRuleModels("tree_lock_1");

        // 规则树决策后返回兜底奖品 AWARD_004
        Award award004 = new Award();
        award004.setAwardId("AWARD_004");
        award004.setAwardName("谢谢参与");
        award004.setAwardType(3);
        award004.setAwardRate(new BigDecimal("0.70"));

        when(cacheService.getAwards("A001")).thenReturn(List.of(award001, award004));
        // 规则树决策：tree_lock_1 判定后返回兜底奖品
        when(treeFactory.process("tree_lock_1", "user001", "A001", "AWARD_001")).thenReturn("AWARD_004");

        DrawResultVO result = lotteryService.draw("user001", "A001");

        assertNotNull(result);
        // 规则树将 AWARD_001 替换为兜底 AWARD_004
        assertEquals("AWARD_004", result.getAwardId());
        assertEquals("谢谢参与", result.getAwardName());

        // 验证规则树被调用
        verify(treeFactory).process("tree_lock_1", "user001", "A001", "AWARD_001");
    }

    @Test
    void draw_success_blacklist_shouldSkipTree() {
        Activity activity = buildActivity();
        when(cacheService.getActivity("A001")).thenReturn(activity);
        when(userParticipateCountMapper.queryByUserIdAndActivityId("user_black_001", "A001")).thenReturn(0);
        when(stockService.deductStock(eq("A001"), any(LocalDateTime.class))).thenReturn(true);

        // 黑名单命中 → 直接返回兜底奖品，跳过规则树
        ILogicChain mockChain = mock(ILogicChain.class);
        when(chainFactory.openLogicChain("A001")).thenReturn(mockChain);
        when(mockChain.logic("user_black_001", "A001")).thenReturn(
                new ChainFactory.ChainResult("AWARD_004", ChainFactory.LogicModel.RULE_BLACKLIST));

        Award award004 = new Award();
        award004.setAwardId("AWARD_004");
        award004.setAwardName("谢谢参与");
        award004.setAwardType(3);
        award004.setAwardRate(new BigDecimal("0.70"));
        when(cacheService.getAwards("A001")).thenReturn(List.of(award004));

        DrawResultVO result = lotteryService.draw("user_black_001", "A001");

        assertEquals("AWARD_004", result.getAwardId());
        assertEquals("谢谢参与", result.getAwardName());
        // 黑名单命中时不应调用规则树
        verify(treeFactory, never()).process(anyString(), anyString(), anyString(), anyString());
    }

    // ========== 辅助方法 ==========

    private Activity buildActivity() {
        Activity activity = new Activity();
        activity.setActivityId("A001");
        activity.setActivityName("测试活动");
        activity.setStatus(1);
        activity.setMaxPerUser(3);
        activity.setRemainStock(100);
        activity.setBeginTime(LocalDateTime.now().minusDays(1));
        activity.setEndTime(LocalDateTime.now().plusDays(1));
        return activity;
    }
}
