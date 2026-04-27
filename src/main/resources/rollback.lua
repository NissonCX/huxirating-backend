-- 原子回滚 Redis 预扣操作
-- 用于撤销 Lua 脚本的预扣操作：恢复库存 + 移除用户
-- KEYS[1] = stockKey (seckill:stock:{voucherId})
-- KEYS[2] = orderKey (seckill:order:{voucherId})
-- KEYS[3] = tokenKey (seckill:token:{voucherId}:{userId})
-- ARGV[1] = voucherId
-- ARGV[2] = userId

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local tokenKey = KEYS[3]
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 尽量保持/补齐 TTL，避免一人一单 Set 永久存在
local stockTtl = redis.call('ttl', stockKey)

-- 前置检查：key 不存在时直接返回，避免 incr 创建脏数据
-- 场景：活动结束后残留回滚请求，incr 会在不存在的 key 上创建值为 1 的永不过期 key
if redis.call('exists', stockKey) == 0 then
    return 0
end

-- 恢复库存
redis.call('incr', stockKey)

-- 移除用户
redis.call('srem', orderKey, userId)

-- 清理 token 映射，避免用户处于"飞行中判重"假象
redis.call('del', tokenKey)

if (stockTtl ~= false and stockTtl ~= nil and stockTtl > 0) then
    redis.call('expire', stockKey, stockTtl)
    redis.call('expire', orderKey, stockTtl)
end

return 1
