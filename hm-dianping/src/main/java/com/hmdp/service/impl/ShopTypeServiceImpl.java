package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;


import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOPTYPE_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1.从缓存中读取店铺类型list
        Set<String> typeList = stringRedisTemplate.opsForZSet().range(SHOPTYPE_LIST_KEY,0,-1);// 获取全部
        //2.先判断有无缓存
        if (CollUtil.isNotEmpty(typeList)) {
            // 集合不为空
            List<ShopType> ShopTypeList = typeList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(ShopTypeList);
        }
        //3.没有缓存，先查再存
        List<ShopType> ShopTypeList = query().orderByAsc("sort").list();
        if(ShopTypeList.isEmpty()){
            return Result.ok("店铺类型列表为空");
        }
        //4.存储redis
        for (ShopType type : ShopTypeList) {
            String json = JSONUtil.toJsonStr(type);
            stringRedisTemplate.opsForZSet().add(SHOPTYPE_LIST_KEY, json, type.getSort());
        }

        return Result.ok(ShopTypeList);
    }
}
