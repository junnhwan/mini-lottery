package io.wanjune.minilottery.lock;

import io.wanjune.minilottery.mapper.ActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 库存扣减服务 — DECR 原子扣减 + SETNX 分段锁
 *
 * 对应简历：「基于 Redis DECR 原子指令扣减库存，结合 SETNX 分段锁机制防止库存恢复后重复消费，
 *           DB 乐观锁兜底防超卖，延迟队列异步落库实现最终一致性」
 *
 * 改造前（Redisson 分布式锁方案）：
 *   tryLock → 读 DB 库存 → 判断 → DB 扣减 → 释放锁
 *   问题：所有请求串行通过锁，锁竞争激烈时大量请求等待超时
 *
 * 改造后（DECR + SETNX 方案）：
 *   DECR 原子扣减 Redis 库存 → SETNX 加分段锁 → 异步 MQ 落库 DB
 *   优势：DECR 是原子操作，不需要加锁，性能从"串行"变为"并行"
 *
 * 核心流程：
 * 1. DECR stock:{activityId} → 原子扣减，返回扣减后的 surplus
 * 2. surplus < 0 → 库存已耗尽，重置为 0，返回失败
 * 3. surplus >= 0 → 扣减成功，SETNX stock:{activityId}_{surplus} 加分段锁
 * 4. SETNX 失败 → 说明这个库存单元已被消费过（运营恢复库存的场景），返回失败
 * 5. SETNX 成功 → 扣减成功，后续由 MQ 异步更新 DB
 *
 * 为什么需要 SETNX 分段锁？
 *   DECR 本身是原子的，正常不会超卖。但有个特殊场景：
 *   运营手动恢复库存（SET stock:xxx 100），此时旧的库存单元可能被重复消费。
 *   SETNX 给每个库存值（99、98、97...）加一把锁，恢复库存后旧锁仍在，防止重复扣减。
 *
 * 参考 big-market: StrategyRepository.subtractionAwardStock()
 *
 * @author zjh
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final RedissonClient redissonClient;
    private final ActivityMapper activityMapper;

    /** Redis key 前缀：活动库存计数器 */
    private static final String STOCK_KEY_PREFIX = "stock:";

    /**
     * DECR 原子扣减库存
     *
     * 可以在这个方法打断点，观察：
     * - surplus 的值（DECR 后的剩余库存）
     * - lockKey 的格式（stock:{activityId}_{surplus}）
     * - SETNX 的返回值（true=加锁成功，false=锁已存在）
     *
     * @param activityId 活动ID
     * @param endTime    活动结束时间（用于计算分段锁的 TTL）
     * @return true=扣减成功，false=库存不足或分段锁冲突
     */
    public boolean deductStock(String activityId, LocalDateTime endTime) {
        String stockKey = STOCK_KEY_PREFIX + activityId;

        // ========== 第 1 步：DECR 原子扣减 ==========
        // Redisson 的 RAtomicLong.decrementAndGet() 底层就是 Redis DECR 命令
        // 原子操作：读取 → 减 1 → 写回，整个过程是不可中断的，不需要加锁
        RAtomicLong atomicStock = redissonClient.getAtomicLong(stockKey);
        long surplus = atomicStock.decrementAndGet();

        // ========== 第 2 步：判断扣减结果 ==========
        if (surplus < 0) {
            // 库存已耗尽（被并发请求扣到负数）
            // 重置为 0，防止无限递减
            atomicStock.set(0);
            log.warn("库存不足，DECR 后 surplus={}, 已重置为 0. activityId={}", surplus, activityId);
            return false;
        }

        // ========== 第 3 步：SETNX 加分段锁 ==========
        // lockKey 格式：stock:{activityId}_{surplus值}
        // 例：库存从 100 扣到 99，lockKey = "stock:A20260310001_99"
        // 每个库存数值对应一把唯一的锁
        String lockKey = stockKey + "_" + surplus;

        // TTL = 活动结束时间 - 当前时间 + 1天缓冲
        // 为什么加 1 天？防止活动结束时间和锁过期时间之间的时钟误差
        boolean locked;
        if (endTime != null) {
            long ttlMillis = Duration.between(LocalDateTime.now(), endTime).toMillis()
                    + Duration.ofDays(1).toMillis();
            locked = redissonClient.getBucket(lockKey).setIfAbsent("lock", Duration.ofMillis(ttlMillis));
        } else {
            // 没有结束时间，设置较长的默认 TTL（7 天）防止永久占用
            locked = redissonClient.getBucket(lockKey).setIfAbsent("lock", Duration.ofDays(7));
        }

        if (!locked) {
            // SETNX 失败：说明这个库存单元已经被消费过
            // 可能是运营恢复库存后的重复消费场景
            log.warn("分段锁加锁失败（库存单元已被消费）lockKey={}", lockKey);
            return false;
        }

        log.info("库存扣减成功 activityId={}, surplus={}, lockKey={}", activityId, surplus, lockKey);

        // surplus == 0 说明最后一个库存被扣走，可以通知清零
        if (surplus == 0) {
            log.info("库存已清零 activityId={}", activityId);
        }

        return true;
    }

    /**
     * 库存预热 — 将 DB 中的剩余库存加载到 Redis
     *
     * 在 StrategyArmory.armory() 装配时调用
     * 用 isExists 判断防止重复初始化覆盖正在扣减的库存值
     *
     * @param activityId  活动ID
     * @param remainStock 剩余库存数量
     */
    public void preheatStock(String activityId, int remainStock) {
        String stockKey = STOCK_KEY_PREFIX + activityId;
        RAtomicLong atomicStock = redissonClient.getAtomicLong(stockKey);

        // 已存在则跳过，防止覆盖正在扣减的库存
        // 场景：应用重启时重新装配，库存可能已经被扣了一部分
        if (atomicStock.isExists()) {
            log.info("库存已存在 Redis，跳过预热 activityId={}, 当前值={}", activityId, atomicStock.get());
            return;
        }

        atomicStock.set(remainStock);
        log.info("库存预热完成 activityId={}, remainStock={}", activityId, remainStock);
    }

    /**
     * 库存回滚 — 超时订单回滚时恢复 Redis 库存
     *
     * 注意：这里只恢复 Redis 库存（INCR），DB 库存由超时消费者单独回滚
     *
     * @param activityId 活动ID
     */
    public void rollbackStock(String activityId) {
        String stockKey = STOCK_KEY_PREFIX + activityId;
        long current = redissonClient.getAtomicLong(stockKey).incrementAndGet();
        log.info("Redis 库存已回滚 activityId={}, 当前库存={}", activityId, current);
    }

    /**
     * 异步落库 — 更新 DB 中的库存（由 MQ 消费者调用）
     *
     * DB 乐观锁兜底：WHERE remain_stock > 0
     * 即使 Redis 和 DB 数据不一致，也不会扣成负数
     *
     * @param activityId 活动ID
     * @return true=更新成功
     */
    public boolean updateDbStock(String activityId) {
        int affected = activityMapper.deductStock(activityId);
        if (affected > 0) {
            log.info("DB 库存扣减成功 activityId={}", activityId);
            return true;
        } else {
            log.warn("DB 库存扣减失败（可能已为0）activityId={}", activityId);
            return false;
        }
    }
}
