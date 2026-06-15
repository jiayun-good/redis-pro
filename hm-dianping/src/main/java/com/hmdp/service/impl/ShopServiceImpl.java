package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Transactional
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    // 尝试获取锁（setIfAbsent = Redis 的 SET NX）
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    //存入缓存并设置逻辑过期时间
    public void saveShop2Redis(Long id, Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期数据
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result queryShopById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //缓存击穿
        //Shop shop = cacheClient.get(CACHE_SHOP_KEY,id,Shop.class,sid->getById(sid),30L,TimeUnit.MINUTES);
        //缓存设置逻辑过期
        //Shop shop = queryWithLogicalExpire(id);

        //用封装的方法
        Shop shop = cacheClient.getWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,sid->getById(sid),30L,TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        //互斥锁，解决缓存击穿
        return Result.ok(shop);
    }
    //缓存穿透
    /*public Shop queryWithPassThrough(Long id){
        //1.判断有无缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        // 2. 缓存命中
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.判断命中的是否是空值
        if (shopJson != null && shopJson.equals("")) {
            return null;
        }

        //4.1 没有缓存,查表
        Shop shop = getById(id);
        //4.2 若查不到
        if(shop == null){
            //4.3 将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //4.4 并且每次都覆盖原空值，以此保证缓存中只有一项空值

            return  null;
        }
        //5. 存入缓存-以string的形式
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }*/


    //缓存击穿
    /*public Shop queryWithMutex(Long id){
        String lockKey = "lock:shop" + id;
        //1.判断有无缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        // 2. 缓存命中
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.判断命中的是否是空值
        if (shopJson != null && shopJson.equals("")) {
            return null;
        }
        //4. 获取缓存锁
        boolean isLock = tryLock(CACHE_SHOP_KEY + id);
        if(!isLock){
            //4.1 获取失败
            try {
                Thread.sleep(50);
                return queryWithMutex(id);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

        }


        try {  // ⭐ 关键：try-finally 保证释放锁
            //4.2 获取锁成功，查询数据库
            Shop shop = getById(id);
            //5. 若查不到
            if (shop == null) {
                //5.1 将空值写入redis
                stringRedisTemplate.opsForValue()
                        .set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6. 存入缓存-以string的形式
            stringRedisTemplate.opsForValue()
                    .set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                            CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        } finally {
            unlock(lockKey);  // ⭐ 无论成功还是异常，都要释放锁
        }
    }*/

    //缓存加逻辑过期时间
    /*public Shop queryWithLogicalExpire(Long id){
        //1.判断有无缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        // 2. 缓存未命中
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        //3.缓存命中
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //3.1 将redisData转成Shop
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否过期
        if (expireTime == null) {
            // 没有设置过期时间，视为永久有效
            return shop;
        }
        if (!LocalDateTime.now().isAfter(expireTime)) {
            // 未过期
            return shop;
        }
        //5.已过期，重建缓存
        if(tryLock(CACHE_SHOP_KEY + id)){
            //4.1 互斥锁获取成功
            try {
                //重建缓存
                this.saveShop2Redis(id,20L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }finally {
                //释放锁
                unlock(CACHE_SHOP_KEY+id);
            }
        }

        //6.返回过期的店铺信息
        return shop;
    }*/

    @Override
    public Result update(Shop shop) {
        if(shop.getId()==null){
            return Result.fail("店铺id不能为空");
        }
        //1.先更新数据库，再删除缓存
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return null;
    }
}
