-- 原子回滚 Redis 预扣操作
-- 用于撤销 Lua 脚本的预扣操作：恢复库存 + 移除用户
local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local tokenKey = 'seckill:token:' .. voucherId .. ':' .. userId

-- 尽量保持/补齐 TTL，避免一人一单 Set 永久存在
local stockTtl = redis.call('ttl', stockKey)

-- 恢复库存
redis.call('incr', stockKey)

-- 移除用户
redis.call('srem', orderKey, userId)

-- 清理 token 映射，避免用户处于“飞行中判重”假象
redis.call('del', tokenKey)

if (stockTtl ~= false and stockTtl ~= nil and stockTtl > 0) then
    redis.call('expire', stockKey, stockTtl)
    redis.call('expire', orderKey, stockTtl)
end

return 1
