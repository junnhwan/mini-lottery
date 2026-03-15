-- ============================================
-- Mini-Lottery 数据库初始化脚本
-- ============================================

CREATE DATABASE IF NOT EXISTS mini_lottery DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE mini_lottery;

-- 1. 活动表
DROP TABLE IF EXISTS activity;
CREATE TABLE activity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id VARCHAR(32) NOT NULL COMMENT '活动ID',
    activity_name VARCHAR(64) NOT NULL COMMENT '活动名称',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待开始 1-进行中 2-已结束',
    total_stock INT NOT NULL COMMENT '总库存',
    remain_stock INT NOT NULL COMMENT '剩余库存',
    max_per_user INT NOT NULL DEFAULT 1 COMMENT '每人最多参与次数',
    begin_time DATETIME NOT NULL COMMENT '活动开始时间',
    end_time DATETIME NOT NULL COMMENT '活动结束时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_activity_id (activity_id)
) ENGINE=InnoDB COMMENT='活动表';

-- 2. 奖品表
DROP TABLE IF EXISTS award;
CREATE TABLE award (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    award_id VARCHAR(32) NOT NULL COMMENT '奖品ID',
    activity_id VARCHAR(32) NOT NULL COMMENT '所属活动ID',
    award_name VARCHAR(64) NOT NULL COMMENT '奖品名称',
    award_type TINYINT NOT NULL COMMENT '1-优惠券 2-实物 3-谢谢参与',
    award_rate DECIMAL(5,4) NOT NULL COMMENT '中奖概率，如 0.1000 表示 10%',
    stock INT NOT NULL DEFAULT 0 COMMENT '奖品库存',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_award_id (award_id),
    INDEX idx_activity_id (activity_id)
) ENGINE=InnoDB COMMENT='奖品表';

-- 3. 抽奖订单表
DROP TABLE IF EXISTS lottery_order;
CREATE TABLE lottery_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL COMMENT '订单号',
    user_id VARCHAR(32) NOT NULL COMMENT '用户ID',
    activity_id VARCHAR(32) NOT NULL COMMENT '活动ID',
    award_id VARCHAR(32) DEFAULT NULL COMMENT '中奖奖品ID',
    award_name VARCHAR(64) DEFAULT NULL COMMENT '中奖奖品名称',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待处理 1-已完成 2-已超时取消',
    expire_time DATETIME DEFAULT NULL COMMENT '订单超时时间（延迟队列用）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_order_id (order_id),
    INDEX idx_user_activity (user_id, activity_id)
) ENGINE=InnoDB COMMENT='抽奖订单表';

-- 4. 用户参与次数表
DROP TABLE IF EXISTS user_participate_count;
CREATE TABLE user_participate_count (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL COMMENT '用户ID',
    activity_id VARCHAR(32) NOT NULL COMMENT '活动ID',
    participate_count INT NOT NULL DEFAULT 0 COMMENT '已参与次数',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_user_activity (user_id, activity_id)
) ENGINE=InnoDB COMMENT='用户参与次数表';

-- 5. 发奖任务表
DROP TABLE IF EXISTS award_task;
CREATE TABLE award_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL COMMENT '关联订单号',
    user_id VARCHAR(32) NOT NULL COMMENT '用户ID',
    award_id VARCHAR(32) NOT NULL COMMENT '奖品ID',
    award_type TINYINT NOT NULL COMMENT '1-优惠券 2-实物 3-谢谢参与',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待发送 1-发送中 2-已完成 3-失败',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_order_id (order_id)
) ENGINE=InnoDB COMMENT='发奖任务表';

-- ============================================
-- 测试数据
-- ============================================

-- 活动1：春季抽奖（max_per_user=3，日常测试用）
INSERT INTO activity (activity_id, activity_name, status, total_stock, remain_stock, max_per_user, begin_time, end_time)
VALUES ('A20260310001', '春季抽奖活动', 1, 1000, 1000, 3, '2026-03-01 00:00:00', '2026-04-01 00:00:00');

INSERT INTO award (award_id, activity_id, award_name, award_type, award_rate, stock, sort) VALUES
('R001', 'A20260310001', '一等奖-AirPods',     2, 0.0100, 10,  1),
('R002', 'A20260310001', '二等奖-10元优惠券',    1, 0.0900, 100, 2),
('R003', 'A20260310001', '三等奖-5元优惠券',     1, 0.2000, 300, 3),
('R004', 'A20260310001', '谢谢参与',            3, 0.7000, 0,   4);

-- 活动2：压测专用（max_per_user=9999，不限次数，方便反复测试）
INSERT INTO activity (activity_id, activity_name, status, total_stock, remain_stock, max_per_user, begin_time, end_time)
VALUES ('A20260310002', '压测专用活动', 1, 10000, 10000, 9999, '2026-03-01 00:00:00', '2026-12-31 23:59:59');

INSERT INTO award (award_id, activity_id, award_name, award_type, award_rate, stock, sort) VALUES
('R005', 'A20260310002', '一等奖-iPhone',       2, 0.0050, 5,    1),
('R006', 'A20260310002', '二等奖-20元优惠券',    1, 0.0950, 200,  2),
('R007', 'A20260310002', '三等奖-5元优惠券',     1, 0.2000, 500,  3),
('R008', 'A20260310002', '谢谢参与',            3, 0.7000, 0,    4);

-- 活动3：已结束活动（status=2，测试校验逻辑）
INSERT INTO activity (activity_id, activity_name, status, total_stock, remain_stock, max_per_user, begin_time, end_time)
VALUES ('A20260310003', '已结束活动', 2, 100, 0, 1, '2026-01-01 00:00:00', '2026-02-01 00:00:00');

-- ============================================
-- Phase 3: 责任链 + 规则树（新增表 + 字段）
-- ============================================

-- 现有表加字段
ALTER TABLE activity ADD COLUMN rule_models VARCHAR(256) DEFAULT NULL COMMENT '责任链规则列表，逗号分隔，如 rule_blacklist,rule_weight';
ALTER TABLE award ADD COLUMN rule_models VARCHAR(256) DEFAULT NULL COMMENT '关联的规则树 ID，如 tree_lock_1';

-- 6. 策略规则表（责任链节点的配置值）
DROP TABLE IF EXISTS strategy_rule;
CREATE TABLE strategy_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id VARCHAR(32) NOT NULL COMMENT '活动ID',
    rule_model VARCHAR(32) NOT NULL COMMENT '规则模型：rule_blacklist / rule_weight',
    rule_value TEXT NOT NULL COMMENT '规则值（格式因 rule_model 而异）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_activity_rule (activity_id, rule_model)
) ENGINE=InnoDB COMMENT='策略规则表（责任链配置）';

-- 7. 规则树定义表
DROP TABLE IF EXISTS rule_tree;
CREATE TABLE rule_tree (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tree_id VARCHAR(32) NOT NULL COMMENT '规则树ID',
    tree_name VARCHAR(64) NOT NULL COMMENT '规则树名称',
    tree_desc VARCHAR(128) DEFAULT NULL COMMENT '规则树描述',
    tree_root_rule_key VARCHAR(32) NOT NULL COMMENT '根节点 rule_key',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_tree_id (tree_id)
) ENGINE=InnoDB COMMENT='规则树定义表';

-- 8. 规则树节点表
DROP TABLE IF EXISTS rule_tree_node;
CREATE TABLE rule_tree_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tree_id VARCHAR(32) NOT NULL COMMENT '规则树ID',
    rule_key VARCHAR(32) NOT NULL COMMENT '节点标识：rule_lock / rule_stock / rule_luck_award',
    rule_desc VARCHAR(64) DEFAULT NULL COMMENT '节点描述',
    rule_value VARCHAR(256) DEFAULT NULL COMMENT '节点配置值（lock阈值 / 兜底奖品ID等）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tree_id (tree_id)
) ENGINE=InnoDB COMMENT='规则树节点表';

-- 9. 规则树节点连线表
DROP TABLE IF EXISTS rule_tree_node_line;
CREATE TABLE rule_tree_node_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tree_id VARCHAR(32) NOT NULL COMMENT '规则树ID',
    rule_node_from VARCHAR(32) NOT NULL COMMENT '起始节点 rule_key',
    rule_node_to VARCHAR(32) NOT NULL COMMENT '目标节点 rule_key',
    rule_limit_type VARCHAR(8) NOT NULL DEFAULT 'EQUAL' COMMENT '限定类型：EQUAL',
    rule_limit_value VARCHAR(16) NOT NULL COMMENT '限定值：ALLOW / TAKE_OVER',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tree_id (tree_id)
) ENGINE=InnoDB COMMENT='规则树节点连线表';

-- ============================================
-- Phase 3 测试数据
-- ============================================

-- 活动1 添加责任链配置
UPDATE activity SET rule_models = 'rule_blacklist,rule_weight' WHERE activity_id = 'A20260310001';

-- 活动1 的一等奖和二等奖需要规则树校验（lock + stock）
UPDATE award SET rule_models = 'tree_lock_1' WHERE award_id = 'R001';
UPDATE award SET rule_models = 'tree_lock_1' WHERE award_id = 'R002';

-- 策略规则：黑名单配置（格式：兜底奖品ID:黑名单用户列表）
INSERT INTO strategy_rule (activity_id, rule_model, rule_value)
VALUES ('A20260310001', 'rule_blacklist', 'R004:user_black_001,user_black_002');

-- 策略规则：权重配置（格式：参与次数阈值:可选奖品ID列表，空格分隔多组）
-- 参与 >= 2 次：可以从所有奖品中抽
-- 参与 >= 3 次：只能抽一等奖、二等奖、谢谢参与（去掉三等奖，集中概率给高奖）
INSERT INTO strategy_rule (activity_id, rule_model, rule_value)
VALUES ('A20260310001', 'rule_weight', '2:R001,R002,R003,R004 3:R001,R002,R004');

-- 规则树定义：tree_lock_1（锁 → 库存 → 兜底）
INSERT INTO rule_tree (tree_id, tree_name, tree_desc, tree_root_rule_key)
VALUES ('tree_lock_1', '抽奖次数锁+库存校验', '先校验抽奖次数是否达标，再校验奖品库存，不满足则走兜底', 'rule_lock');

-- 规则树节点
INSERT INTO rule_tree_node (tree_id, rule_key, rule_desc, rule_value) VALUES
('tree_lock_1', 'rule_lock',       '抽奖次数锁：参与次数 >= 阈值才放行', '2'),
('tree_lock_1', 'rule_stock',      '奖品库存校验：DECR 扣减 per-award 库存', NULL),
('tree_lock_1', 'rule_luck_award', '兜底奖品：谢谢参与', 'R004');

-- 规则树连线
-- rule_lock → ALLOW → rule_stock（次数够，继续查库存）
-- rule_lock → TAKE_OVER → rule_luck_award（次数不够，直接兜底）
-- rule_stock → TAKE_OVER → null（库存扣成功，终止，确认发奖）
-- rule_stock → ALLOW → rule_luck_award（库存不足，走兜底）
INSERT INTO rule_tree_node_line (tree_id, rule_node_from, rule_node_to, rule_limit_type, rule_limit_value) VALUES
('tree_lock_1', 'rule_lock',  'rule_stock',      'EQUAL', 'ALLOW'),
('tree_lock_1', 'rule_lock',  'rule_luck_award', 'EQUAL', 'TAKE_OVER'),
('tree_lock_1', 'rule_stock', 'rule_luck_award', 'EQUAL', 'ALLOW');
