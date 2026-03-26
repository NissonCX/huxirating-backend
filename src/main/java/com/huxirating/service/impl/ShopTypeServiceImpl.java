package com.huxirating.service.impl;

import cn.hutool.json.JSONUtil;
import com.huxirating.dto.Result;
import com.huxirating.entity.ShopType;
import com.huxirating.mapper.ShopTypeMapper;
import com.huxirating.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huxirating.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 商铺类型服务 — Redis 缓存优先，未命中再查 DB
 *
 * @author Nisson
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private static final String CACHE_KEY = "cache:shop:type";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // L1: Redis
        String json = stringRedisTemplate.opsForValue().get(CACHE_KEY);
        if (json != null) {
            return Result.ok(JSONUtil.toList(JSONUtil.parseArray(json), ShopType.class));
        }

        // L2: DB
        List<ShopType> types = query().orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set(CACHE_KEY, JSONUtil.toJsonStr(types),
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(types);
    }
}
