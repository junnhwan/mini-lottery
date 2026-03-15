package io.wanjune.minilottery.service.rule.chain;

import io.wanjune.minilottery.cache.MultiLevelCacheService;
import io.wanjune.minilottery.mapper.po.Activity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 责任链工厂 — 根据 DB 配置动态组装链（Phase 3）
 *
 * 对应简历：「规则配置存 DB，支持热插拔」
 *
 * 组装流程：
 * 1. 读取 activity.rule_models（如 "rule_blacklist,rule_weight"）
 * 2. 按逗号分割，依次从 Spring 容器获取 Bean
 * 3. 用 appendNext() 串联成链表
 * 4. 末尾始终追加 DefaultLogicChain（终端节点）
 * 5. 组装结果缓存到 ConcurrentHashMap，避免重复组装
 *
 * 热插拔实现：修改 DB 中 activity.rule_models → 清除缓存 → 下次调用自动重新组装
 *
 * 为什么用 ApplicationContext.getBean() 而不是 Map 注入？
 * - 链节点用了 @Scope(PROTOTYPE)，每次 getBean 返回新实例
 * - Map 注入只在启动时注入一次（单例），拿到的是同一个实例
 * - PROTOTYPE 确保每个活动的链互不影响（next 指针不同）
 *
 * 参考 big-market: DefaultChainFactory.java
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainFactory {

    private final ApplicationContext applicationContext;
    private final MultiLevelCacheService cacheService;

    /** 缓存已组装的链（key=activityId, value=链头节点） */
    private final ConcurrentHashMap<String, ILogicChain> chainCache = new ConcurrentHashMap<>();

    /**
     * 获取指定活动的责任链（有缓存）
     *
     * @param activityId 活动ID
     * @return 链头节点（从头开始调用 logic 即可遍历整条链）
     */
    public ILogicChain openLogicChain(String activityId) {
        // 先查缓存
        ILogicChain cached = chainCache.get(activityId);
        if (cached != null) {
            return cached;
        }

        // 从 DB 读取链节点配置
        Activity activity = cacheService.getActivity(activityId);
        String ruleModels = activity != null ? activity.getRuleModels() : null;

        // 组装链
        ILogicChain head;
        if (ruleModels == null || ruleModels.isBlank()) {
            // 没有配置规则，直接走 DefaultLogicChain
            head = applicationContext.getBean("rule_default", ILogicChain.class);
            log.info("活动无链规则配置，使用默认链 activityId={}", activityId);
        } else {
            // 按逗号分割，依次组装
            String[] ruleModelArray = ruleModels.split(",");
            head = applicationContext.getBean(ruleModelArray[0].trim(), ILogicChain.class);
            ILogicChain current = head;
            for (int i = 1; i < ruleModelArray.length; i++) {
                ILogicChain nextNode = applicationContext.getBean(ruleModelArray[i].trim(), ILogicChain.class);
                current = current.appendNext(nextNode);
            }
            // 末尾追加 DefaultLogicChain（终端节点，保证链一定有出口）
            current.appendNext(applicationContext.getBean("rule_default", ILogicChain.class));
            log.info("链组装完成 activityId={}, rules=[{}] → default", activityId, ruleModels);
        }

        // 缓存
        chainCache.put(activityId, head);
        return head;
    }

    // ==================== 内部类 ====================

    /**
     * 链节点命中类型枚举
     */
    public enum LogicModel {
        RULE_BLACKLIST,
        RULE_WEIGHT,
        RULE_DEFAULT
    }

    /**
     * 链执行结果
     */
    public record ChainResult(String awardId, LogicModel logicModel) {
    }
}
