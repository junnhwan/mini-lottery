package io.wanjune.minilottery;

import io.wanjune.minilottery.lock.StockService;
import io.wanjune.minilottery.mapper.ActivityMapper;
import io.wanjune.minilottery.mapper.po.Activity;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 并发扣库存测试
 *
 * @author zjh
 * @since 2026/3/10 19:16
 */
@Slf4j
@SpringBootTest
public class ApiTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private ActivityMapper activityMapper;

    /**
     * 并发测试：50 个线程同时扣库存，验证不超卖
     *
     * 测试前请确保 DB 中 remain_stock 足够（测试数据为 1000）
     */
    @Test
    public void testConcurrentDeductStock() throws InterruptedException {
        String activityId = "A20260310001";
        int threadCount = 50;

        // 记录扣减前的库存
        Activity before = activityMapper.queryByActivityId(activityId);
        int stockBefore = before.getRemainStock();
        log.info("扣减前库存: {}", stockBefore);

        // 并发工具
        CountDownLatch startLatch = new CountDownLatch(1);   // 所有线程同时起跑
        CountDownLatch endLatch = new CountDownLatch(threadCount);  // 等所有线程结束
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();  // 等待发令枪
                    boolean result = stockService.deductStock(activityId, LocalDateTime.now().plusDays(1));
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

        startLatch.countDown();   // 发令枪：所有线程同时开始
        endLatch.await();         // 等待全部完成
        executor.shutdown();

        // 查询扣减后的库存
        Activity after = activityMapper.queryByActivityId(activityId);
        int stockAfter = after.getRemainStock();

        log.info("========== 测试结果 ==========");
        log.info("并发线程数: {}", threadCount);
        log.info("成功扣减: {} 次", successCount.get());
        log.info("失败（锁竞争/库存不足）: {} 次", failCount.get());
        log.info("库存变化: {} → {}", stockBefore, stockAfter);
        log.info("实际扣减数量: {}", stockBefore - stockAfter);

        // 断言：实际扣减数量 = 成功次数（核心验证点，不相等说明超卖了）
        assertEquals(successCount.get(), stockBefore - stockAfter, "库存扣减数量应等于成功次数，否则存在超卖");
    }
}
