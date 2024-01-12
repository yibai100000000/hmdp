package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIME=1704067200L;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    public long nextId(String keyPrefix){

        //生成时间戳

        //获取秒
        LocalDateTime now = LocalDateTime.now();
        long second =now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=second-BEGIN_TIME;

        //生成序列号
        //区分时间
        String dateKey=now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long increment = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + dateKey);

        //拼接并返回
        return timestamp << 32 | increment;
    }


}
