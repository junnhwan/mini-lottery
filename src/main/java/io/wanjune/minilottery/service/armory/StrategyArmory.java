package io.wanjune.minilottery.service.armory;

import io.wanjune.minilottery.common.enums.ActivityStatus;
import io.wanjune.minilottery.lock.StockService;
import io.wanjune.minilottery.mapper.ActivityMapper;
import io.wanjune.minilottery.mapper.AwardMapper;
import io.wanjune.minilottery.mapper.po.Activity;
import io.wanjune.minilottery.mapper.po.Award;
import io.wanjune.minilottery.service.algorithm.IDrawAlgorithm;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 策略装配服务（兵工厂）
 *
 * 对应简历：「通过空间换时间策略将概率装配至 Redis 哈希表，实现 O(1) 复杂度抽奖；
 *           概率范围较大时自适应切换为有序区间表 + 二分查找」
 *
 * 整体职责：
 * 1. armory（装配）：读取 DB 中的奖品概率 → 计算 rateRange → 选择算法 → 构建查找表存 Redis
 * 2. draw（抽奖）：根据 activityId 查找已装配的算法类型 → 调用对应算法的 dispatch → 返回 awardId
 *
 * 核心设计 — 自适应算法选择：
 * - 计算最小概率 → 推导 rateRange（如 0.01 → 100，0.001 → 1000）
 * - rateRange ≤ 10000（阈值）→ 使用 O(1) 哈希表算法（空间换时间，内存可接受）
 * - rateRange > 10000 → 使用 O(log n) 二分查找算法（节省内存，查找速度略慢）
 * - 算法类型记录到 Redis，抽奖时自动路由到正确的算法实现
 *
 * 装配时机：
 * - 应用启动时自动装配所有进行中的活动（@PostConstruct）
 * - 手动调用 armory(activityId) 重新装配（活动配置变更后）
 *
 * 参考 big-market: StrategyArmoryDispatch.java + AbstractStrategyAlgorithm.java
 *
 * @author zjh
 */
@Slf4j
@Service
public class StrategyArmory {

    private final AwardMapper awardMapper;
    private final ActivityMapper activityMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StockService stockService;

    /**
     * Spring 自动注入：Map<beanName, bean> 形式注入所有 IDrawAlgorithm 实现
     *
     * 这是 Spring 的一个技巧：当你有多个同类型的 Bean 时，可以用 Map 自动注入
     * key 是 Bean 名称（@Component("o1Algorithm") 中指定的名字）
     * value 是 Bean 实例
     *
     * 注入后 algorithmMap = {"o1Algorithm": O1Algorithm实例, "oLogNAlgorithm": OLogNAlgorithm实例}
     *
     * 参考 big-market: StrategyArmoryDispatch 构造函数中的 Map<String, IAlgorithm> algorithmMap
     */
    private final Map<String, IDrawAlgorithm> algorithmMap;

    /** 算法选择阈值：rateRange 超过此值时切换为 O(log n) */
    private static final int ALGORITHM_THRESHOLD = 10000;

    /** Redis key：存储算法类型（"o1Algorithm" 或 "oLogNAlgorithm"） */
    private static final String ALGORITHM_KEY_PREFIX = "strategy:algorithm:";

    public StrategyArmory(AwardMapper awardMapper,
                          ActivityMapper activityMapper,
                          RedisTemplate<String, Object> redisTemplate,
                          StockService stockService,
                          Map<String, IDrawAlgorithm> algorithmMap) {
        this.awardMapper = awardMapper;
        this.activityMapper = activityMapper;
        this.redisTemplate = redisTemplate;
        this.stockService = stockService;
        this.algorithmMap = algorithmMap;
    }

    /**
     * 应用启动时自动装配所有进行中的活动
     *
     * 为什么要在启动时装配？
     * - 装配涉及 DB 查询 + Redis 写入，有一定耗时
     * - 放在启动时完成，避免用户第一次抽奖时的冷启动延迟
     * - 分布式环境下，每个实例都会装配，但 Redis 是共享的，互不影响
     *
     * 注意：用 try-catch 包裹，装配失败不阻塞应用启动
     * 失败的活动可以后续通过手动调用 armory(activityId) 重新装配
     */
    @PostConstruct
    public void init() {
        try {
            log.info("========== 开始装配抽奖策略 ==========");
            List<Activity> activeActivities = activityMapper.queryByStatus(ActivityStatus.ACTIVE.getCode());
            for (Activity activity : activeActivities) {
                armory(activity.getActivityId());
            }
            log.info("========== 抽奖策略装配完成，共装配 {} 个活动 ==========", activeActivities.size());
        } catch (Exception e) {
            // 装配失败不阻塞应用启动，避免 DB/Redis 暂时不可用导致整个应用起不来
            log.warn("========== 抽奖策略装配失败（不影响启动，可手动重新装配）==========", e);
        }
    }

    /**
     * 装配指定活动的抽奖策略
     *
     * 可以在这个方法打断点，观察完整的装配流程：
     * 1. 从 DB 查奖品概率
     * 2. 计算 rateRange
     * 3. 选择 O(1) 还是 O(log n)
     * 4. 调用对应算法的 armory 方法
     *
     * @param activityId 活动ID
     */
    public void armory(String activityId) {
        log.info("开始装配活动 activityId={}", activityId);

        // 1. 查询该活动下的所有奖品（含概率）
        List<Award> awards = awardMapper.queryByActivityId(activityId);
        if (awards == null || awards.isEmpty()) {
            log.warn("活动无奖品配置，跳过装配 activityId={}", activityId);
            return;
        }

        // 2. 计算 rateRange（概率精度范围）
        //    找到最小概率 → 推导出需要多少个"槽位"才能精确表达所有概率
        //    例：最小概率 0.01 → rateRange = 100（百分位精度，需要 100 个槽位）
        //    例：最小概率 0.005 → rateRange = 1000（千分位精度）
        int rateRange = computeRateRange(awards);
        log.info("概率精度计算完成 activityId={}, rateRange={}", activityId, rateRange);

        // 3. 根据 rateRange 自适应选择算法
        //    ≤ 10000：O(1) 哈希表（100~10000 个 Redis Hash field，内存可接受）
        //    > 10000：O(log n) 二分查找（避免存上万个 Hash field）
        String algorithmName;
        if (rateRange <= ALGORITHM_THRESHOLD) {
            algorithmName = "o1Algorithm";
            log.info("rateRange={} ≤ {}，选择 O(1) 算法", rateRange, ALGORITHM_THRESHOLD);
        } else {
            algorithmName = "oLogNAlgorithm";
            log.info("rateRange={} > {}，选择 O(log n) 算法", rateRange, ALGORITHM_THRESHOLD);
        }

        // 4. 调用选中的算法进行装配
        IDrawAlgorithm algorithm = algorithmMap.get(algorithmName);
        algorithm.armory(activityId, awards, rateRange);

        // 5. 记录该活动使用的算法类型（抽奖时需要路由到正确的算法）
        redisTemplate.opsForValue().set(ALGORITHM_KEY_PREFIX + activityId, algorithmName);

        // 6. 库存预热 — 将 DB 中的剩余库存加载到 Redis（Phase 2 新增）
        //    DECR 扣减的前提是库存已经在 Redis 中，所以必须在装配时预热
        //    preheatStock 内部会判断 isExists，已存在则跳过，防止覆盖正在扣减的值
        Activity activity = activityMapper.queryByActivityId(activityId);
        if (activity != null) {
            stockService.preheatStock(activityId, activity.getRemainStock());
        }

        log.info("活动装配完成 activityId={}, algorithm={}", activityId, algorithmName);
    }

    /**
     * 执行抽奖 — 从 Redis 概率查找表中获取一个随机奖品ID
     *
     * @param activityId 活动ID
     * @return 命中的奖品ID
     */
    public String draw(String activityId) {
        // 1. 从 Redis 查找该活动使用的算法类型
        Object algorithmName = redisTemplate.opsForValue().get(ALGORITHM_KEY_PREFIX + activityId);
        if (algorithmName == null) {
            throw new RuntimeException("活动未装配策略，请先调用 armory. activityId=" + activityId);
        }

        // 2. 根据算法名称获取对应的算法实现（Spring Bean）
        IDrawAlgorithm algorithm = algorithmMap.get(algorithmName.toString());
        if (algorithm == null) {
            throw new RuntimeException("未找到算法实现: " + algorithmName);
        }

        // 3. 调用算法的 dispatch 方法（O(1) 或 O(log n)）
        return algorithm.dispatch(activityId);
    }

    /**
     * 计算概率精度范围（rateRange）
     *
     * 算法：
     * 1. 找到所有奖品中的最小概率
     * 2. 去除尾部零（0.0100 → 0.01）
     * 3. 小数位数 = rateRange 的指数（0.01 → 2位 → 10^2 = 100）
     *
     * 为什么要 stripTrailingZeros？
     * - MySQL DECIMAL(5,4) 存储 0.01 时实际为 0.0100（4 位小数）
     * - 如果不去尾零，rateRange = 10^4 = 10000（浪费空间）
     * - 去尾零后 rateRange = 10^2 = 100（更合理）
     *
     * 参考 big-market: AbstractStrategyAlgorithm.convert() 方法
     * 我们这里用更简洁的 BigDecimal.scale() 实现
     *
     * @param awards 奖品列表
     * @return rateRange（如 100、1000、10000）
     */
    private int computeRateRange(List<Award> awards) {
        // 找最小概率
        BigDecimal minRate = awards.stream()
                .map(Award::getAwardRate)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ONE);

        // 去除尾部零，获取有效小数位数
        // 0.0100 → stripTrailingZeros → 0.01 → scale() = 2
        // 0.0050 → stripTrailingZeros → 0.005 → scale() = 3
        BigDecimal stripped = minRate.stripTrailingZeros();
        int scale = stripped.scale();

        // rateRange = 10 的 scale 次方
        // scale=2 → rateRange=100, scale=3 → rateRange=1000
        return (int) Math.pow(10, Math.max(scale, 1));
    }
}
