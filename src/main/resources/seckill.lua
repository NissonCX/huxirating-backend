-- 秒杀资格校验脚本：原子性完成库存检查、一人一单校验、库存扣减
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local tokenKey = 'seckill:token:' .. voucherId .. ':' .. userId

-- 将 stockKey 的 TTL 复制给 orderKey，避免 seckill:order:{voucherId} 永久占用内存
local stockTtl = redis.call('ttl', stockKey)

-- 校验库存
local stock = redis.call('get', stockKey)
local stockNum = tonumber(stock)
if (stock == nil or stockNum == nil or stockNum <= 0) then
    return 1
end

-- 校验一人一单
if (redis.call('sismember', orderKey, userId) == 1) then
    if (stockTtl ~= false and stockTtl ~= nil and stockTtl > 0) then
        redis.call('expire', orderKey, stockTtl)
    end
    return 2
end

-- 扣减库存 + 记录用户
redis.call('decr', stockKey)
redis.call('sadd', orderKey, userId)

-- 记录 token -> orderId 映射，解决“飞行中判重”无法追踪的问题
if (orderId ~= false and orderId ~= nil and tostring(orderId) ~= '') then
    redis.call('set', tokenKey, orderId)
    if (stockTtl ~= false and stockTtl ~= nil and stockTtl > 0) then
        redis.call('expire', tokenKey, stockTtl)
    end
end

if (stockTtl ~= false and stockTtl ~= nil and stockTtl > 0) then
    redis.call('expire', orderKey, stockTtl)
end

return 0
