package io.wanjune.minilottery.lock;

import io.wanjune.minilottery.mapper.ActivityMapper;
import io.wanjune.minilottery.mapper.po.Activity;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 测试：Redis + Lua 库存扣减
 *
 * 测试目标：
 * 1. 验证库存预热正确写入 Redis
 * 2. 验证 Lua 脚本原子扣减基本流程
 * 3. 验证 SETNX 分段锁生成
 * 4. 验证库存耗尽后返回失败
 * 5. 验证 rollbackStock（INCR）正确恢复
 * 6. 验证 updateDbStock 异步落库
 * 7. 验证并发扣减无超卖
 *
 * 调试建议：
 * - 在 deductStock() 打断点，观察 surplus 值和 lockKey 格式
 * - 用 Redis 客户端 KEYS stock:test_* 查看生成的分段锁
 * - 并发测试前确保 DB 中 A20260310002 库存充足
 *
 * 注意：使用 test_ 前缀的 key 隔离测试数据，每个测试前后清理
 */
@Slf4j
@SpringBootTest
class StockServiceTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ActivityMapper activityMapper;

    /** 测试用 key 前缀，避免影响真实数据 */
    private static final String TEST_KEY = "test_stock_phase2";

    /** 活动结束时间（测试用，1 天后） */
    private final LocalDateTime testEndTime = LocalDateTime.now().plusDays(1);

    @BeforeEach
    void cleanup() {
        // 清理测试用 Redis key
        redissonClient.getAtomicLong("stock:" + TEST_KEY).delete();
        // 清理可能残留的分段锁（stock:test_stock_phase2_0 ~ _9）
        for (int i = 0; i < 20; i++) {
            redissonClient.getBucket("stock:" + TEST_KEY + "_" + i).delete();
        }
    }

    // ==================== 库存预热测试 ====================

    @Test
    @DisplayName("preheatStock：预热后 Redis 值正确")
    void test_preheat_sets_redis_value() {
        stockService.preheatStock(TEST_KEY, 100);

        long value = redissonClient.getAtomicLong("stock:" + TEST_KEY).get();
        assertEquals(100, value, "预热后 Redis 库存应为 100");

        log.info("库存预热验证通过 ✓ Redis 值 = {}", value);
    }

    @Test
    @DisplayName("preheatStock：已存在时跳过，不覆盖")
    void test_preheat_skip_if_exists() {
        // 先预热为 100
        stockService.preheatStock(TEST_KEY, 100);

        // 手动扣减一些（模拟正在使用）
        redissonClient.getAtomicLong("stock:" + TEST_KEY).set(73);

        // 再次预热，应跳过
        stockService.preheatStock(TEST_KEY, 100);

        long value = redissonClient.getAtomicLong("stock:" + TEST_KEY).get();
        assertEquals(73, value, "二次预热不应覆盖正在使用的库存值");

        log.info("预热幂等验证通过 ✓ 库存保持 73 未被覆盖");
    }

    // ==================== Lua 扣减测试 ====================

    @Test
    @DisplayName("deductStock：正常扣减成功")
    void test_deduct_success() {
        stockService.preheatStock(TEST_KEY, 10);

        boolean result = stockService.deductStock(TEST_KEY, testEndTime);

        assertTrue(result, "库存充足时扣减应成功");

        long surplus = redissonClient.getAtomicLong("stock:" + TEST_KEY).get();
        assertEquals(9, surplus, "扣减后库存应为 9");

        log.info("Lua 扣减验证通过 ✓ surplus = {}", surplus);
    }

    @Test
    @DisplayName("deductStock：库存耗尽返回 false")
    void test_deduct_fail_when_empty() {
        // 预热库存为 1
        stockService.preheatStock(TEST_KEY, 1);

        // 第一次扣减：成功（1 → 0）
        assertTrue(stockService.deductStock(TEST_KEY, testEndTime), "第一次扣减应成功");

        // 第二次扣减：失败（0 → -1 → 重置为 0）
        assertFalse(stockService.deductStock(TEST_KEY, testEndTime), "库存为 0 时扣减应失败");

        long surplus = redissonClient.getAtomicLong("stock:" + TEST_KEY).get();
        assertEquals(0, surplus, "库存耗尽后应重置为 0，不为负数");

        log.info("库存耗尽验证通过 ✓ surplus = {} (已重置)", surplus);
    }

    // ==================== SETNX 分段锁测试 ====================

    @Test
    @DisplayName("deductStock：SETNX 分段锁生成验证")
    void test_setnx_segment_lock_created() {
        stockService.preheatStock(TEST_KEY, 10);

        // 扣减：10 → 9，应生成 lockKey = stock:test_stock_phase2_9
        stockService.deductStock(TEST_KEY, testEndTime);

        boolean lockExists = redissonClient.getBucket("stock:" + TEST_KEY + "_9").isExists();
        assertTrue(lockExists, "SETNX 分段锁应存在: stock:" + TEST_KEY + "_9");

        // 再扣一次：9 → 8，应生成 lockKey = stock:test_stock_phase2_8
        stockService.deductStock(TEST_KEY, testEndTime);

        boolean lock2Exists = redissonClient.getBucket("stock:" + TEST_KEY + "_8").isExists();
        assertTrue(lock2Exists, "SETNX 分段锁应存在: stock:" + TEST_KEY + "_8");

        log.info("SETNX 分段锁验证通过 ✓ lockKey _9 和 _8 均已创建");
    }

    // ==================== 库存回滚测试 ====================

    @Test
    @DisplayName("rollbackStock：INCR 回滚后库存恢复")
    void test_rollback_increments_stock() {
        stockService.preheatStock(TEST_KEY, 10);

        // 扣 3 次
        stockService.deductStock(TEST_KEY, testEndTime);
        stockService.deductStock(TEST_KEY, testEndTime);
        stockService.deductStock(TEST_KEY, testEndTime);

        long afterDeduct = redissonClient.getAtomicLong("stock:" + TEST_KEY).get();
        assertEquals(7, afterDeduct, "扣 3 次后应为 7");

        // 回滚 1 次
        stockService.rollbackStock(TEST_KEY);

        long afterRollback = redissonClient.getAtomicLong("stock:" + TEST_KEY).get();
        assertEquals(8, afterRollback, "回滚 1 次后应为 8");

        log.info("INCR 回滚验证通过 ✓ 7 → {}", afterRollback);
    }

    // ==================== 异步落库 DB 测试 ====================

    @Test
    @DisplayName("updateDbStock：DB 乐观锁扣减验证")
    void test_update_db_stock() {
        // 使用压测活动 A20260310002（不限次数，库存充足）
        String activityId = "A20260310002";

        Activity before = activityMapper.queryByActivityId(activityId);
        int stockBefore = before.getRemainStock();
        log.info("DB 库存扣减前: {}", stockBefore);

        if (stockBefore <= 0) {
            log.warn("跳过测试：活动 {} 的 DB 库存已为 0，请重置后重试", activityId);
            return;
        }

        boolean success = stockService.updateDbStock(activityId);
        assertTrue(success, "DB 库存扣减应成功");

        Activity after = activityMapper.queryByActivityId(activityId);
        assertEquals(stockBefore - 1, after.getRemainStock(), "DB 库存应减 1");

        // 恢复：回滚刚才的扣减
        activityMapper.rollbackStock(activityId);
        log.info("updateDbStock 验证通过 ✓ DB 库存 {} → {} → 已恢复", stockBefore, stockBefore - 1);
    }

    // ==================== 并发扣减测试 ====================

    @Test
    @DisplayName("并发扣减：30 线程抢 20 个库存，无超卖")
    void test_concurrent_deduct_no_oversell() throws InterruptedException {
        int stock = 20;
        int threadCount = 30;

        stockService.preheatStock(TEST_KEY, stock);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean result = stockService.deductStock(TEST_KEY, testEndTime);
                    if (result) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        long surplus = redissonClient.getAtomicLong("stock:" + TEST_KEY).get();

        log.info("========== 并发扣减结果 ==========");
        log.info("库存: {}, 线程数: {}", stock, threadCount);
        log.info("成功: {}, 失败: {}", successCount.get(), failCount.get());
        log.info("Redis 剩余库存: {}", surplus);

        // 核心断言
        assertEquals(stock, successCount.get(), "成功次数应等于初始库存（每个库存单元只被消费一次）");
        assertEquals(threadCount - stock, failCount.get(), "失败次数 = 线程数 - 库存");
        assertEquals(0, surplus, "所有库存扣完后 Redis 应为 0");

        log.info("并发扣减无超卖验证通过 ✓");
    }
}
