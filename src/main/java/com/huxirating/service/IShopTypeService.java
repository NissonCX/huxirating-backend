package com.huxirating.service;

import com.huxirating.dto.Result;
import com.huxirating.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Nisson
 * @since 2025-10-01
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeList();
}
