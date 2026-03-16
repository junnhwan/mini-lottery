package io.wanjune.minilottery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wanjune.minilottery.common.BusinessException;
import io.wanjune.minilottery.mapper.DailySignInMapper;
import io.wanjune.minilottery.mapper.UserCreditAccountMapper;
import io.wanjune.minilottery.mapper.UserParticipateCountMapper;
import io.wanjune.minilottery.mapper.po.DailySignIn;
import io.wanjune.minilottery.mapper.po.UserCreditAccount;
import io.wanjune.minilottery.mq.consumer.RebateConsumer;
import io.wanjune.minilottery.mq.producer.MQProducer;
import io.wanjune.minilottery.service.impl.UserBehaviorServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 5 用户行为服务单元测试（纯 Mockito，不启动 Spring 容器）
 *
 * 测试覆盖：
 * 1. 签到成功 → 积分增加 + MQ 消息注册
 * 2. 重复签到 → 抛 BusinessException(1007)
 * 3. 积分兑换成功 → 积分扣减 + MQ 消息注册
 * 4. 积分不足 → 抛 BusinessException(1008)
 * 5. RebateConsumer 消费 SIGN_IN 消息 → addBonusCount 被调用
 * 6. RebateConsumer 消费 CREDIT_EXCHANGE 消息 → addBonusCount 被调用
 *
 * @author zjh
 * @since 2026/3/16
 */
@ExtendWith(MockitoExtension.class)
class UserBehaviorTest {

    @Mock private DailySignInMapper dailySignInMapper;
    @Mock private UserCreditAccountMapper userCreditAccountMapper;
    @Mock private MQProducer mqProducer;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private UserBehaviorServiceImpl userBehaviorService;

    // RebateConsumer 单独构造（它不在 Service 中，独立测试）
    private RebateConsumer rebateConsumer;
    @Mock private UserParticipateCountMapper userParticipateCountMapper;

    @BeforeEach
    void setUp() {
        // 初始化事务同步管理器（afterCommit 回调需要）
        TransactionSynchronizationManager.initSynchronization();

        // RebateConsumer 需要手动注入 mock
        rebateConsumer = new RebateConsumer(userParticipateCountMapper, objectMapper);
    }

    @AfterEach
    void tearDown() {
        // 清理事务同步管理器，避免影响其他测试
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== 测试 1：签到成功 ====================

    /**
     * 正常签到：插入记录 + 积分 +10 + 注册 afterCommit 回调
     *
     * 面试点：MQ 在事务提交后发送（afterCommit），
     * 单元测试没有真实事务，需要手动触发 afterCommit 回调来验证
     */
    @Test
    void test_signIn_success() {
        // 执行签到
        userBehaviorService.signIn("user001", "A20260310001");

        // 验证：签到记录被插入
        verify(dailySignInMapper).insert(any(DailySignIn.class));

        // 验证：积分 +10
        verify(userCreditAccountMapper).insertOrAddCredit("user001", 10);

        // 手动触发 afterCommit 回调（模拟事务提交）
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        // 验证：MQ 消息发送（JSON 包含 SIGN_IN）
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqProducer).sendRebateMessage(msgCaptor.capture());
        String message = msgCaptor.getValue();
        assertTrue(message.contains("SIGN_IN"));
        assertTrue(message.contains("user001"));
        assertTrue(message.contains("A20260310001"));
    }

    // ==================== 测试 2：重复签到 ====================

    /**
     * 同一天重复签到 → DuplicateKeyException → BusinessException(1007)
     *
     * 面试点：幂等设计用 UNIQUE 索引，比"先查再插"更可靠
     * "先查再插"有 TOCTOU 并发窗口，UNIQUE 索引是 DB 级别的保证
     */
    @Test
    void test_signIn_duplicate() {
        doThrow(new DuplicateKeyException("Duplicate entry"))
                .when(dailySignInMapper).insert(any(DailySignIn.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userBehaviorService.signIn("user001", "A20260310001"));

        assertEquals(1007, ex.getCode());
        assertTrue(ex.getMessage().contains("今日已签到"));

        // 积分不应该被增加
        verify(userCreditAccountMapper, never()).insertOrAddCredit(anyString(), anyInt());
    }

    // ==================== 测试 3：积分兑换成功 ====================

    /**
     * 积分余额充足 → 扣减成功 + 注册 afterCommit 回调
     */
    @Test
    void test_creditExchange_success() {
        // deductCredit 返回 1 表示扣减成功
        when(userCreditAccountMapper.deductCredit("user001", 50)).thenReturn(1);

        userBehaviorService.creditExchange("user001", "A20260310001", 50);

        // 验证积分扣减
        verify(userCreditAccountMapper).deductCredit("user001", 50);

        // 手动触发 afterCommit
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        // 验证 MQ 消息
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqProducer).sendRebateMessage(msgCaptor.capture());
        assertTrue(msgCaptor.getValue().contains("CREDIT_EXCHANGE"));
    }

    // ==================== 测试 4：积分不足 ====================

    /**
     * 积分余额不足 → deductCredit 返回 0 → BusinessException(1008)
     *
     * 面试点：用 UPDATE WHERE available_credit >= cost 做原子校验，
     * 而不是先 SELECT 再 UPDATE（并发窗口问题）
     */
    @Test
    void test_creditExchange_insufficient() {
        // deductCredit 返回 0 表示余额不足
        when(userCreditAccountMapper.deductCredit("user001", 100)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userBehaviorService.creditExchange("user001", "A20260310001", 100));

        assertEquals(1008, ex.getCode());
        assertTrue(ex.getMessage().contains("积分不足"));
    }

    // ==================== 测试 5：Consumer 处理签到返利消息 ====================

    /**
     * RebateConsumer 收到 SIGN_IN 类型消息 → 调用 addBonusCount(+1)
     */
    @Test
    void test_rebateConsumer_signIn() {
        String message = """
                {"userId":"user001","activityId":"A20260310001","type":"SIGN_IN","rewardCount":1}
                """;

        rebateConsumer.onMessage(message);

        verify(userParticipateCountMapper).addBonusCount("user001", "A20260310001", 1);
    }

    // ==================== 测试 6：Consumer 处理积分兑换消息 ====================

    /**
     * RebateConsumer 收到 CREDIT_EXCHANGE 类型消息 → 调用 addBonusCount(+1)
     */
    @Test
    void test_rebateConsumer_creditExchange() {
        String message = """
                {"userId":"user002","activityId":"A20260310001","type":"CREDIT_EXCHANGE","rewardCount":1}
                """;

        rebateConsumer.onMessage(message);

        verify(userParticipateCountMapper).addBonusCount("user002", "A20260310001", 1);
    }
}
