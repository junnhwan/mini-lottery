package io.wanjune.minilottery.lock;

import io.wanjune.minilottery.mapper.ActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 库存扣减服务 — Redis Lua 脚本原子扣减
 *
 * 对应简历：「使用 Redis + Lua 脚本原子扣减库存，结合 SETNX 分段锁机制防止库存恢复后重复消费，
 *           DB 乐观锁兜底防超卖，MQ 异步落库实现最终一致性」
 *
 * 改造前（Redisson 分布式锁方案）：
 *   tryLock → 读 DB 库存 → 判断 → DB 扣减 → 释放锁
 *   问题：所有请求串行通过锁，锁竞争激烈时大量请求等待超时
 *
 * 改造后（Lua 脚本方案）：
 *   Lua 脚本内 DECR + SETNX 一次性原子执行 → 异步 MQ 落库 DB
 *   优势：利用 Redis 单线程执行 Lua 的特性，DECR 和 SETNX 在同一脚本中原子完成，
 *         无需加锁，无竞态条件，性能从"串行"变为"并行"
 *
 * Lua 脚本核心流程（stock_deduct.lua）：
 * 1. DECR stock:{activityId} → 原子扣减，返回扣减后的 surplus
 * 2. surplus < 0 → 库存已耗尽，重置为 0，返回 -1
 * 3. surplus >= 0 → SETNX stock:{activityId}_{surplus} 加分段锁
 * 4. SETNX 失败 → 返回 -2（库存单元已被消费过，运营恢复库存场景）
 * 5. SETNX 成功 → 返回 surplus（成功），后续由 MQ 异步更新 DB
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
    private final StringRedisTemplate stringRedisTemplate;
    private final ActivityMapper activityMapper;

    /** Redis key 前缀：活动库存计数器 */
    private static final String STOCK_KEY_PREFIX = "stock:";

    /** 加载 Lua 脚本（应用启动时解析一次，后续复用 SHA1 缓存） */
    private static final RedisScript<Long> DEDUCT_STOCK_SCRIPT;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/stock_deduct.lua"));
        script.setResultType(Long.class);
        DEDUCT_STOCK_SCRIPT = script;
    }

    /**
     * Lua 脚本原子扣减库存
     *
     * 整个 DECR + SETNX 在一个 Lua 脚本中执行，利用 Redis 单线程特性保证原子性。
     * 相比之前的两步 Java 调用（先 DECR 再 SETNX），消除了两步之间的竞态窗口。
     *
     * @param activityId 活动ID
     * @param endTime    活动结束时间（用于计算分段锁的 TTL）
     * @return true=扣减成功，false=库存不足或分段锁冲突
     */
    public boolean deductStock(String activityId, LocalDateTime endTime) {
        String stockKey = STOCK_KEY_PREFIX + activityId;

        // 计算分段锁 TTL（毫秒）
        long ttlMs;
        if (endTime != null) {
            ttlMs = Duration.between(LocalDateTime.now(), endTime).toMillis()
                    + Duration.ofDays(1).toMillis();
        } else {
            ttlMs = Duration.ofDays(7).toMillis();
        }

        // 执行 Lua 脚本：DECR + SETNX 原子操作
        Long result = stringRedisTemplate.execute(
                DEDUCT_STOCK_SCRIPT,
                Collections.singletonList(stockKey),
                String.valueOf(ttlMs)
        );

        if (result == null || result == -1) {
            log.warn("库存不足，Lua 脚本返回 {}. activityId={}", result, activityId);
            return false;
        }
        if (result == -2) {
            log.warn("分段锁加锁失败（库存单元已被消费）activityId={}", activityId);
            return false;
        }

        log.info("库存扣减成功 activityId={}, surplus={}", activityId, result);

        if (result == 0) {
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
