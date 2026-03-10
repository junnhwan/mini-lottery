package io.wanjune.minilottery.lock;

import io.wanjune.minilottery.mapper.ActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁库存扣减服务
 *
 * @author zjh
 * @since 2026/3/10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final RedissonClient redissonClient;
    private final ActivityMapper activityMapper;

    private static final String STOCK_LOCK_PREFIX = "lock:stock:";

    /**
     * 使用 Redisson 分布式锁扣减库存
     *
     * 流程：获取锁 → DB扣减（乐观锁兜底）→ 释放锁
     * 看门狗机制：不设 leaseTime，Redisson 默认 30s 自动续期，防止业务没执行完锁就过期
     *
     * @param activityId 活动ID
     * @return true=扣减成功，false=库存不足或获取锁失败
     */
    public boolean deductStock(String activityId) {
        String lockKey = STOCK_LOCK_PREFIX + activityId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试获取锁，最多等待 3 秒，不设 leaseTime 启用看门狗自动续期
            boolean acquired = lock.tryLock(3, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("获取库存锁失败 activityId={}", activityId);
                return false;
            }

            log.info("获取库存锁成功 activityId={}", activityId);

            // DB 乐观锁扣减：WHERE remain_stock > 0
            int affected = activityMapper.deductStock(activityId);
            if (affected <= 0) {
                log.warn("库存不足 activityId={}", activityId);
                return false;
            }

            log.info("库存扣减成功 activityId={}", activityId);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取库存锁被中断 activityId={}", activityId, e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("释放库存锁 activityId={}", activityId);
            }
        }
    }
}
