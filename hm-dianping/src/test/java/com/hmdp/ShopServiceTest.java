package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class ShopServiceTest {
    @Autowired
    private IShopService shopService;
    @Test
    void saveShop(){
        shopService.saveShop2Redis(1L,300L);
    }
}
