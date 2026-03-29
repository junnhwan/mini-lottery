-- 库存扣减 Lua 脚本：GET + DECR + SETNX 原子操作
--
-- 将 GET 检查库存、DECR 扣减库存和 SETNX 加分段锁合并到一个 Lua 脚本中，
-- 利用 Redis 单线程执行 Lua 的特性保证整个流程的原子性。
--
-- KEYS[1] = stock:{activityId}    库存计数器 key
-- ARGV[1] = TTL（毫秒）           分段锁的过期时间
--
-- 返回值：
--   >= 0  扣减成功，返回扣减后的剩余库存（surplus）
--   -1    库存不足（GET 后 <= 0）
--   -2    分段锁冲突（SETNX 失败，库存单元已被消费过）

local stockKey = KEYS[1]
local ttlMs = tonumber(ARGV[1])

-- Step 1: GET 当前库存
local currentStock = tonumber(redis.call('GET', stockKey) or '0')

-- Step 2: 库存耗尽判断
if currentStock <= 0 then
    return -1
end

-- Step 3: DECR 原子扣减
local surplus = redis.call('DECR', stockKey)

-- Step 4: SETNX 分段锁（SET NX PX 毫秒级 TTL）
local lockKey = stockKey .. '_' .. surplus
local locked = redis.call('SET', lockKey, 'lock', 'NX', 'PX', ttlMs)

if not locked then
    return -2
end

return surplus
