package io.wanjune.minilottery.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.wanjune.minilottery.mapper.ActivityMapper;
import io.wanjune.minilottery.mapper.AwardMapper;
import io.wanjune.minilottery.mapper.po.Activity;
import io.wanjune.minilottery.mapper.po.Award;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存服务（L1 Caffeine + L2 Redis + L3 DB）
 *
 * @author zjh
 * @since 2026/3/10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiLevelCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ActivityMapper activityMapper;
    private final AwardMapper awardMapper;

    private static final String REDIS_ACTIVITY_PREFIX = "cache:activity:";
    private static final String REDIS_AWARDS_PREFIX = "cache:awards:";
    private static final long REDIS_TTL_MINUTES = 10;

    /** L1 本地缓存：活动信息，最多 100 条，60 秒过期 */
    private final Cache<String, Activity> activityCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    /** L1 本地缓存：奖品列表，最多 100 条，60 秒过期 */
    private final Cache<String, List<Award>> awardsCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    /**
     * 查询活动信息（L1 → L2 → L3）
     */
    public Activity getActivity(String activityId) {
        // L1: Caffeine
        Activity activity = activityCache.getIfPresent(activityId);
        if (activity != null) {
            log.debug("活动缓存命中 L1 Caffeine activityId={}", activityId);
            return activity;
        }

        // L2: Redis
        String redisKey = REDIS_ACTIVITY_PREFIX + activityId;
        activity = (Activity) redisTemplate.opsForValue().get(redisKey);
        if (activity != null) {
            log.debug("活动缓存命中 L2 Redis activityId={}", activityId);
            activityCache.put(activityId, activity);
            return activity;
        }

        // L3: DB
        activity = activityMapper.queryByActivityId(activityId);
        log.debug("活动缓存未命中，查询 DB activityId={}", activityId);
        if (activity != null) {
            redisTemplate.opsForValue().set(redisKey, activity, REDIS_TTL_MINUTES, TimeUnit.MINUTES);
            activityCache.put(activityId, activity);
        }
        return activity;
    }

    /**
     * 查询奖品列表（L1 → L2 → L3）
     */
    @SuppressWarnings("unchecked")
    public List<Award> getAwards(String activityId) {
        // L1: Caffeine
        List<Award> awards = awardsCache.getIfPresent(activityId);
        if (awards != null) {
            log.debug("奖品缓存命中 L1 Caffeine activityId={}", activityId);
            return awards;
        }

        // L2: Redis
        String redisKey = REDIS_AWARDS_PREFIX + activityId;
        awards = (List<Award>) redisTemplate.opsForValue().get(redisKey);
        if (awards != null) {
            log.debug("奖品缓存命中 L2 Redis activityId={}", activityId);
            awardsCache.put(activityId, awards);
            return awards;
        }

        // L3: DB
        awards = awardMapper.queryByActivityId(activityId);
        log.debug("奖品缓存未命中，查询 DB activityId={}", activityId);
        if (awards != null && !awards.isEmpty()) {
            redisTemplate.opsForValue().set(redisKey, awards, REDIS_TTL_MINUTES, TimeUnit.MINUTES);
            awardsCache.put(activityId, awards);
        }
        return awards;
    }
}
