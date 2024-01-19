package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private VoucherOrderServiceImpl voucherOrderService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    //事务不能加在这个方法上，会导致这个事务提交之前锁被释放从而导致并发问题
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);

        //判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }

        Long userId= UserHolder.getUser().getId();
        //synchronized (userId.toString().intern()) {
        //获取锁（分布式）
        //创建锁对象
        final SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock(5);
        if(!isLock){
            //获取锁失败
            return Result.fail("重复下单");
        }
        try{
            return voucherOrderService.createVoucherOrder(voucherId);
        }finally {
            lock.unLock();
        }
        //}
    }

    @Transactional
    //事务失效:直接使用this调用，spring事务是使用代理对象实现
    //处理方式，自己调用自己
    public Result createVoucherOrder(Long voucherId){
        //一人一单
        Long userId= UserHolder.getUser().getId();


            //查询订单
            int count= query()
                    .eq("user_id",userId)
                    .eq("voucher_id",voucherId)
                    .count();

            //扣减库存
            final boolean success = iSeckillVoucherService.update()
                    .setSql("stock=stock-1")
                    .eq("voucher_id", voucherId)
                    .gt("stock",0)
                    .update();
            if(!success){
                return Result.fail("库存不足");
            }

            //创建订单
            //添加订单id，用户id，代金券id
            VoucherOrder voucherOrder=new VoucherOrder();

            long orderId=redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);

            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);


            //返回订单id
            return Result.ok(orderId);
    }
}
