package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 商铺类型服务 — 多级缓存：Caffeine(L1) → Redis(L2) → DB
 *
 * @author Nisson
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private static final String CACHE_KEY = "cache:shop:type";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<String, List<ShopType>> shopTypeCache;

    @Override
    public Result queryTypeList() {
        // L1: Caffeine
        List<ShopType> types = shopTypeCache.getIfPresent(CACHE_KEY);
        if (types != null) {
            return Result.ok(types);
        }
        // L2: Redis
        String json = stringRedisTemplate.opsForValue().get(CACHE_KEY);
        if (json != null) {
            types = JSONUtil.toList(JSONUtil.parseArray(json), ShopType.class);
            shopTypeCache.put(CACHE_KEY, types);
            return Result.ok(types);
        }
        // L3: DB
        types = query().orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set(CACHE_KEY, JSONUtil.toJsonStr(types),
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        shopTypeCache.put(CACHE_KEY, types);
        return Result.ok(types);
    }
}
