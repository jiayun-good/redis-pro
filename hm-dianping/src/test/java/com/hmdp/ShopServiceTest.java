package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
public class ShopServiceTest {
    @Autowired
    private IShopService shopService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void saveShop(){
        shopService.saveShop2Redis(1L,300L);
    }

    /*店铺分类和经纬度存入缓存*/
    @Test
    void loadShopData(){
        List<Shop> list = shopService.list();
        //根据店铺类型分类
        Map<Long, List<Shop>> shopMap = list.stream().collect(Collectors.groupingBy(shop -> shop.getTypeId()));
        for (Map.Entry<Long, List<Shop>> entry : shopMap.entrySet()) {
            String key = "shop:geo:" + entry.getKey();
            //店铺list
            List<Shop> value = entry.getValue();
            for (Shop shop : value) {
                stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
            }

        }
    }
}
