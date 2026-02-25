package com.huxirating.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huxirating.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author Nisson
 * @since 2025-10-01
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
