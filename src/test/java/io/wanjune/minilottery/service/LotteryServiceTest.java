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
 * 注意：Phase 1 把 DrawAlgorithm 替换为 StrategyArmory，Phase 2 把 deductStock 签名改为 (String, LocalDateTime)
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

        // Phase 1：DrawAlgorithm → StrategyArmory，返回 awardId 字符串
        when(strategyArmory.draw("A001")).thenReturn("AWARD_001");

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
        // 注意：MQ 发送在 afterCommit() 中，单元测试无真实事务不会触发
        // MQ 发送验证由 StockServiceTest（集成测试）覆盖
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
