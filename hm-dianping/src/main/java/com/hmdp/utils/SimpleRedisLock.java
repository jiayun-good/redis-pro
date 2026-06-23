package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;

    private String key;

    public SimpleRedisLock(String key,StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
        this.key = "lock:" + key;

    }
    //用uuid区分不同jvm
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    //使用lua脚本释放锁
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    /*
    * key 表示“锁谁”
    * value 表示“谁拿到了锁”
    * */
    @Override
    public boolean tryLock(long timeoutSec) {
        //用线程id区分同个jvm下的不同线程
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean sucess = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadId,timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(sucess);
    }



    @Override
    /**
     * 释放锁
     * @param lockKey 锁的key（例如 "lock:order:123"）
     * @param lockValue 锁的值（当前线程的唯一标识，用于验证锁的持有者）
     * @return true-释放成功，false-释放失败（锁不存在或不是当前线程持有）
     */
    public void unLock(){
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取redis中标识
        String id = stringRedisTemplate.opsForValue().get(key);
        //使用lua脚本操作释放锁，保证原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                threadId
                );
    }
    /*public void unLock() {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取redis中标识
        String id = stringRedisTemplate.opsForValue().get(key);
        if(threadId.equals(id)){
           stringRedisTemplate.delete(key);
        }

    }*/
}
