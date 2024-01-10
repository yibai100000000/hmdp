package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;


@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    //将任意JAVA对象序列化为JSON并存储在string类型的key中，并且可以设置TTL
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //将任意JAVA对象序列化为JSON并存储在string类型的key中，并且可以设置逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
       //设置逻辑过期
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    //指定前缀，id，查询类型，查询方法，超时时间与单位
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id , Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key=keyPrefix+id;
        //从redis查询商铺缓存
        String json=stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否存在
        if(StrUtil.isNotBlank(json)){
            //存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断是否命中的是空值
        if(json!=null){
            //返回空值
            return null;
        }
        //不存在,函数式编程,从数据库中查找
        R r=dbFallback.apply(id);
        //不存在，返回错误
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在，写入redis
        this.set(key,r,time,unit);
        return r;

    }


    //根据指定的key查询缓存，并反序列化为指定类型，利用逻辑过期解决缓存击穿问题
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id ,Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key=keyPrefix+id;

        //查缓存
        String jsonStr = stringRedisTemplate.opsForValue().get(key);

        //缓存是否为空
        if(StrUtil.isBlank(jsonStr)){
            return null;
        }

        //命中，将JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        R r=JSONUtil.toBean((JSONObject) redisData.getData(),type);

        //判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //没有过期，返回对象
            return r;
        }

        //过期，委托重建缓存
        String lockKey=LOCK_SHOP_KEY+id;
        //尝试获取锁
        if(tryLock(lockKey)){

            //双检
            String Co = stringRedisTemplate.opsForValue().get(key);
            //命中，将JSON反序列化为对象
            RedisData checkData = JSONUtil.toBean(Co, RedisData.class);
            R checkR=JSONUtil.toBean((JSONObject) redisData.getData(),type);

            if(checkData.getExpireTime().isAfter(LocalDateTime.now())){
                return checkR;
            }

            //成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //1.查询店铺数据
                    R r1=dbFallback.apply(id);
                    //2.封装逻辑过期时间3.写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    //释放锁
                    unLock(key);
                }
            });
        }
        //返回过期的商城信息
        return r;

    }

    //工具
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newSingleThreadExecutor();

    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


}
