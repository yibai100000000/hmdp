package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newSingleThreadExecutor();


    @Override
    public Result queryById(Long id) {

        //互斥锁
        //Result result = mutexQuery(id);

        //逻辑过期
        //Result result = logicExpireQuery(id);

        //工具类方法:解决缓存穿透
        Shop shop=cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private Result mutexQuery(Long id){
        String key=CACHE_SHOP_KEY+id;

        Object o = redisTemplate.opsForValue().get(key);
        //避免缓存穿透
        if(o!=null && !o.equals("null")){
            return Result.ok(o);
        }else if(o!=null && o.equals("null")){
            return Result.fail("商铺不存在");
        }

        //实现缓存重建
        String lockKey=LOCK_SHOP_KEY+id;
        Shop shop= null;
        try {
            while(queryWithMutex(lockKey)){
                //申请失败进行一段时间的等待，重新申请锁
                Thread.sleep(50);
            }
            //双检
            if(redisTemplate.opsForValue().get(key)!=null){
                if(o.equals("null")){
                    return Result.fail("商铺不存在");
                }else{
                    return Result.ok(o);
                }
            }
            shop = getById(id);
            if(shop==null){

                //向redis中写入空对象
                redisTemplate.opsForValue().set(key,"null",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("商铺不存在");
            }
            redisTemplate.opsForValue().set(key,shop,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            //释放锁
            unLock(lockKey);
        }
        return Result.ok(shop);
    }

    private Result logicExpireQuery(Long id){
        String key=CACHE_SHOP_KEY+id;

        //查缓存
        Object o = redisTemplate.opsForValue().get(key);

        if(o==null){
            return Result.fail("商家不存在");
        }
        //命中缓存
        RedisData redisData= (RedisData) o;
        //判断是否过期
        LocalDateTime expireTime=redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //没有过期，返回对象
            return Result.ok(redisData.getData());
        }

        //过期，委托重建缓存
        String lockKey=LOCK_SHOP_KEY+id;
        //尝试获取锁
        if(queryWithMutex(lockKey)){

            //双检
            Object Co = redisTemplate.opsForValue().get(key);
            //命中缓存
            RedisData CredisData= (RedisData) o;

            if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
                return Result.ok(CredisData.getData());
            }

            //成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    saveShop2Redis(id,LOCK_SHOP_TTL);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    //释放锁
                    unLock(key);
                }
            });
        }
        //返回过期的商城信息
        return Result.ok(redisData.getData());


    }

    public void saveShop2Redis(Long id,Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        redisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,redisData);
    }


    private boolean queryWithMutex(String key){
        //申请在redis上的变量,CAS?
        //申请成功进行查询操作，写入对象
        return tryLock(key);
    }

    private boolean tryLock(String key){
        Boolean b = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    private void unLock(String key){
        redisTemplate.delete(key);
    }

    @Override
    public Result updateShop(Shop shop) {
        if(shop.getId()==null){
            return Result.fail("店铺id不能为空");
        }
        //先修改数据库
        updateById(shop);
        //再删除缓存
        String key=CACHE_SHOP_KEY+shop.getId();
        redisTemplate.delete(key);

        return Result.ok();

    }
}
