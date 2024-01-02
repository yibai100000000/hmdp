package com.hmdp.service.impl;

import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public List<ShopType> queryType() {
        List<ShopType> list;
        String key=CACHE_SHOPTYPE_KEY+"list";
        Object o = redisTemplate.opsForValue().get(key);

        if(o!=null){
            list= (List<ShopType>) o;
            return list;
        }
        list= query().orderByAsc("sort").list();
        if(list==null){
            return null;
        }
        redisTemplate.opsForValue().set(key,list,CACHE_SHOPTYPE_TTL, TimeUnit.MINUTES);
        return list;
    }
}
