package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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



    //1.将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        String jsonVlaue = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key,jsonVlaue,time,timeUnit);
    }

    //2.将任意lava对象序列化为ison并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        //整理逻辑过期数据
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //3. 根据指定的key查询缓存，并反序列化为指定类型
    //互斥锁在这里解决的是缓存击穿（热点key同时过期的问题），缓存空值解决的缓存穿透（访问数据库没有的值）
    public  <T,ID> T get(String cacheKey, ID id, Class<T> type, Function<ID, T> dbfallback, Long time, TimeUnit timeUnit){
        String key = cacheKey+id;
        //1.从缓存中查
        String str = stringRedisTemplate.opsForValue().get(key);
        //2.缓存命中
        if(!StrUtil.isBlank(str)){
            return JSONUtil.toBean(str,type);
        }
        // 3. 判断命中的是否是空值（防止缓存穿透）
        if (str != null && str.equals("")) {
            return null;
        }
        //4.缓存未命中,获取互斥锁
        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY+id);
        if(!isLock){
            //4.1 获取失败
            try {
                Thread.sleep(50);
                return get(cacheKey,id,type,dbfallback,time, timeUnit);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        //5.获取锁成功
        try {  // ⭐ 关键：try-finally 保证释放锁
            //5.1 获取锁成功，查询数据库
            T data = dbfallback.apply(id);
            //5.2 若查不到
            if (data == null) {
                //5.3 将空值写入redis
                stringRedisTemplate.opsForValue()
                        .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6. 缓存重建
            this.set(key, JSONUtil.toJsonStr(data), time, timeUnit);
            return data; //因为是从数据库查到的，所以不需要类型转换
        } finally {
            unlock(RedisConstants.LOCK_SHOP_KEY+id);  // ⭐ 无论成功还是异常，都要释放锁
        }

    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    //4. 根据指定的key查询缓存，并反序列化为指定类型，利用逻辑过期解决缓存击穿问题
    public  <T,ID> T getWithLogicalExpire(String cacheKey, ID id, Class<T> type, Function<ID, T> dbfallback, Long time, TimeUnit timeUnit){
        //缓存key
        String key = cacheKey+id;
        //1.从缓存中查
        String str = stringRedisTemplate.opsForValue().get(key);
        //2.缓存未命中
        if(StrUtil.isBlank(str)){
            return null;
        }

        // 3. 命中
        //3.1 将redisData转成Shop
        RedisData redisData = JSONUtil.toBean(str, RedisData.class);
        T data = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //3.2 获取逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //3.3 判断是否过期
        if (LocalDateTime.now().isBefore(expireTime)) {
            // 未过期
            return data;
        }

        //4.缓存未命中,获取互斥锁
        //lock key
        String lockKey = "lock:" + cacheKey + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {

                    T freshData = dbfallback.apply(id);

                    if (freshData == null) {
                        stringRedisTemplate.opsForValue()
                                .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return;
                    }

                    RedisData newRedisData = new RedisData();
                    newRedisData.setData(freshData);
                    newRedisData.setExpireTime(
                            LocalDateTime.now()
                                    .plusSeconds(timeUnit.toSeconds(time))
                    );

                    stringRedisTemplate.opsForValue()
                            .set(key, JSONUtil.toJsonStr(newRedisData));

                } finally {
                    unlock(lockKey);
                }

            });
        }

        // 无论拿没拿到锁,都返回旧数据
        return data;


    }
}
