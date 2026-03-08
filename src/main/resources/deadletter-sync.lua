-- 死信处理：原子化库存同步 + 移除用户
-- 解决 Redis/MySQL 不一致导致的死循环问题
--
-- 参数：
--   ARGV[1] = voucherId
--   ARGV[2] = userId
--   ARGV[3] = mysqlStock (MySQL 实际库存)
--
-- 返回值：
--   0 = 处理成功
--   1 = Redis key 不存在（无需处理）

local voucherId = ARGV[1]
local userId = ARGV[2]
local mysqlStock = tonumber(ARGV[3])

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 获取当前 Redis 库存
local redisStock = redis.call('get', stockKey)
if redisStock == false then
    -- key 不存在，可能是活动已结束或数据已清理
    return 1
end

local redisStockNum = tonumber(redisStock)

-- 【核心逻辑】原子化同步
if redisStockNum > mysqlStock then
    -- Redis 库存 > MySQL 库存，说明有"虚假库存"
    -- 必须降级，否则用户会反复抢购成功但 MySQL 失败
    redis.call('set', stockKey, mysqlStock)
else
    -- Redis 库存 <= MySQL 库存，正常回滚 +1
    redis.call('incr', stockKey)
end

-- 移除用户（允许下次抢购）
redis.call('srem', orderKey, userId)

return 0