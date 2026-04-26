-- 原子回滚 Redis 预扣操作
-- 用于撤销 Lua 脚本的预扣操作：恢复库存 + 移除用户
local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 恢复库存
redis.call('incr', stockKey)

-- 移除用户
redis.call('srem', orderKey, userId)

return 1
