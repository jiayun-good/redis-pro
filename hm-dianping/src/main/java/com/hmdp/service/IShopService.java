package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public interface IShopService extends IService<Shop> {

    Result queryShopById(Long id);

    Result update(Shop shop);
    //保存店铺到Redis，带逻辑过期时间
    void saveShop2Redis(Long id, Long expireSeconds);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
