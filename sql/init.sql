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
