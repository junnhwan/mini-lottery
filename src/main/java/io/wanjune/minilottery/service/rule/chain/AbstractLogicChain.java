package io.wanjune.minilottery.service.rule.chain;

import lombok.extern.slf4j.Slf4j;

/**
 * 责任链基类 — 持有 next 指针，实现链表串联机制
 *
 * 所有链节点（BlackList/Weight/Default）继承此类，
 * 只需关注自己的业务逻辑（logic 方法），串联机制由基类处理。
 *
 * 为什么用抽象类而不是直接在接口中实现 default 方法？
 * - next 指针是可变状态（private 字段），接口不能有实例字段
 * - 抽象类可以持有状态，更适合链表模式
 *
 * 参考 big-market: AbstractLogicChain.java
 */
@Slf4j
public abstract class AbstractLogicChain implements ILogicChain {

    /** 下一个链节点（链表 next 指针） */
    private ILogicChain next;

    @Override
    public ILogicChain next() {
        return next;
    }

    @Override
    public ILogicChain appendNext(ILogicChain next) {
        this.next = next;
        return next;
    }

    /**
     * 当前节点的标识名（用于日志），由子类提供
     */
    protected abstract String ruleModel();
}
