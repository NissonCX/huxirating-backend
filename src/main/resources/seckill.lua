-- 秒杀资格校验脚本：原子性完成库存检查、一人一单校验、库存扣减
local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 校验库存
local stock = redis.call('get', stockKey)
local stockNum = tonumber(stock)
if (stock == nil or stockNum == nil or stockNum <= 0) then
    return 1
end

-- 校验一人一单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 扣减库存 + 记录用户
redis.call('decr', stockKey)
redis.call('sadd', orderKey, userId)

return 0
