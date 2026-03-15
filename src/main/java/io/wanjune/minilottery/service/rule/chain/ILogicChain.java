package io.wanjune.minilottery.service.rule.chain;

/**
 * 责任链节点接口（Phase 3）
 *
 * 对应简历：「基于责任链模式实现前置过滤（黑名单→权重→默认），节点通过数据库配置实现热插拔」
 *
 * 链节点通过 next 指针串联，每个节点决定是否"拦截"请求：
 * - 拦截（如黑名单命中）：直接返回结果，不调 next
 * - 放行：调用 next().logic() 交给下一个节点
 *
 * 为什么用链表而不是 List 遍历？
 * - 链表的 next 指针让每个节点自己决定是否传递，更灵活
 * - 面试时"责任链模式"的标准实现就是 next 指针
 *
 * 参考 big-market: ILogicChain.java + ILogicChainArmory.java
 */
public interface ILogicChain {

    /**
     * 执行链节点逻辑
     *
     * @param userId     用户ID
     * @param activityId 活动ID
     * @return 链执行结果（awardId + 命中的节点类型）
     */
    ChainFactory.ChainResult logic(String userId, String activityId);

    /**
     * 获取下一个节点
     */
    ILogicChain next();

    /**
     * 设置下一个节点，返回 next 节点方便链式调用
     */
    ILogicChain appendNext(ILogicChain next);
}
