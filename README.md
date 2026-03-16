# Mini-Lottery 迷你抽奖平台

> 参考学习了一个 DDD 架构的营销平台项目，理解其设计思路后，用经典三层 MVC 架构独立实现的简化版本。在库存扣减、缓存、限流、订单超时等方面选择了不同的技术方案。

## 技术栈

| 技术 | 用途 |
|------|------|
| Spring Boot 3.5 + Java 21 | 基础框架 |
| MyBatis + MySQL | 持久层 |
| Redis + Caffeine | 多级缓存（L1 本地 + L2 Redis + L3 DB） |
| Redisson | 分布式锁基础设施 |
| RabbitMQ | 异步发奖 + 延迟队列超时回滚 + 签到返利 |
| Guava RateLimiter | 令牌桶限流 + 超频自动拉黑 |
| Lombok | 减少样板代码 |
| Docker | 容器化部署 |

## 核心功能

### 1. O(1) 哈希表抽奖算法
概率装配至 Redis 哈希表，O(1) 随机查找。当概率精度 > 10000 时自适应切换 O(log n) 二分查找，平衡内存与性能。

### 2. DECR + SETNX 库存扣减
Redis DECR 原子扣减库存 + SETNX 分段锁防超卖，延迟队列异步落库实现 Redis 实时 + DB 最终一致。

### 3. 责任链 + 规则树
责任链前置过滤（黑名单 → 权重 → 默认）+ 规则树后置决策（锁 → 库存 → 兜底）。规则配置存 DB，支持热插拔。

### 4. Guava 令牌桶限流
用户级令牌桶限流（`permitsPerSecond=1.0`），连续被拒超阈值自动拉黑，被限流用户返回兜底奖励而非报错。

### 5. RabbitMQ 异步解耦
- 异步发奖：抽奖后通过 MQ 异步处理奖品发放
- 延迟队列：订单超时自动回滚库存（TTL + 死信队列）
- 签到返利：每日签到 +10 积分 → MQ → 增加抽奖机会
- 积分兑换：消耗积分 → MQ → 增加抽奖机会

### 6. 多级缓存
L1 Caffeine（60s TTL）→ L2 Redis（10min TTL）→ L3 MySQL，逐级降级查询，减少热点数据对 DB 的压力。

## 项目结构

```
io.wanjune.minilottery/
├── controller/          # REST 接口
├── service/             # 业务逻辑
│   ├── impl/
│   ├── algorithm/       # 抽奖算法（O1 / OLogN）
│   ├── armory/          # 策略装配
│   ├── rule/            # 责任链 + 规则树
│   └── vo/
├── mapper/              # MyBatis DAO
│   └── po/
├── cache/               # 多级缓存
├── lock/                # 库存扣减
├── interceptor/         # 限流注解 + AOP
├── mq/                  # RabbitMQ 生产者 + 消费者
├── config/              # 配置类
└── common/              # 统一返回、异常、枚举
```

## 快速启动

### 环境要求
- JDK 21+
- MySQL 8.0+
- Redis 7.0+
- RabbitMQ 3.12+

### 本地运行

```bash
# 1. 初始化数据库
mysql -u root -p < sql/init.sql

# 2. 修改配置
# 编辑 src/main/resources/application.yml，配置 MySQL/Redis/RabbitMQ 连接

# 3. 打包运行
mvn clean package -DskipTests
java -jar target/mini-lottery-0.0.1-SNAPSHOT.jar
```

访问 http://localhost:8080 打开前端页面。

### Docker 部署

```bash
# 1. 本地打包
mvn clean package -DskipTests
cp target/mini-lottery-0.0.1-SNAPSHOT.jar devops/app.jar

# 2. 构建镜像
cd devops && docker build -t mini-lottery .

# 3. 导出镜像（用于上传到无法访问 Docker Hub 的服务器）
docker save mini-lottery -o mini-lottery.tar

# 4. 服务器上加载并启动
docker load -i mini-lottery.tar
docker-compose up -d
```

> `docker-compose.yml` 使用 `network_mode: host`，容器直接使用宿主机网络访问 MySQL/Redis/RabbitMQ。

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/lottery/draw?userId=x&activityId=x` | 抽奖 |
| GET | `/api/lottery/records?userId=x&activityId=x` | 查询抽奖记录 |
| POST | `/api/user/sign-in?userId=x&activityId=x` | 每日签到（+10 积分） |
| GET | `/api/user/credit?userId=x` | 查询积分 |
| POST | `/api/user/credit-exchange?userId=x&activityId=x&cost=10` | 积分兑换抽奖机会 |

## 压测

使用 JMeter 进行压力测试，测试计划位于 `stress-test/lottery-benchmark.jmx`。

三个测试场景：
1. **基准 QPS**：50 并发测系统最大吞吐量
2. **限流效果**：同一用户高频请求，验证令牌桶限流
3. **库存扣减**：100 并发抢 1000 库存，验证不超卖

```bash
# 命令行运行压测
jmeter -n -t stress-test/lottery-benchmark.jmx \
  -JSERVER_HOST=服务器IP \
  -l stress-test/result.csv \
  -e -o stress-test/report/
```



## 测试数据

| activity_id | 名称 | 库存 | 每人限制 | 状态 |
|-------------|------|------|----------|------|
| A20260310001 | 日常活动 | 1,000 | 3 次 | 进行中 |
| A20260310002 | 压测活动 | 10,000 | 9,999 次 | 进行中 |
| A20260310003 | 已结束活动 | 0 | 1 次 | 已结束 |
