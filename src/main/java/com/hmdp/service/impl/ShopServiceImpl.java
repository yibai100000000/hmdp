package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
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


    @Override
    public Result queryById(Long id) {

        String key=CACHE_SHOP_KEY+id;

        Object o = redisTemplate.opsForValue().get(key);
        if(o!=null && !o.equals("null")){
            return Result.ok(o);
        }else if(o.equals("null")){
            return Result.fail("商铺不存在");
        }

        Shop shop=getById(id);
        if(shop==null){

            //向redis中写入空对象
            redisTemplate.opsForValue().set(key,"null",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("商铺不存在");
        }
        redisTemplate.opsForValue().set(key,shop,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
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
