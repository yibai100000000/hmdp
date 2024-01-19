package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements  ILcok{

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    public static final String KEY_PREFIX = "lock:";

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        long threadId=Thread.currentThread().getId();
        //获取锁
        Boolean success= stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX+name,threadId+"",timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        //释放锁
        stringRedisTemplate.delete(KEY_PREFIX+name);
    }
}
