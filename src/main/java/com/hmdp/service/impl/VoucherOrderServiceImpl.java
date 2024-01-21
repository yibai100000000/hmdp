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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Autowired
    private RedissonClient redissonClient;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    //该方法在bean构造注入完毕执行，初始化方法执行前执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run(){
            while(true){
                //获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucher(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
                

            }
        }
        
    }

    private void handleVoucher(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取锁失败
            log.error("重复下单");
            return;
        }
        try{
            return voucherOrderService.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }

    }


    @Override
    //事务不能加在这个方法上，会导致这个事务提交之前锁被释放从而导致并发问题
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId=UserHolder.getUser().getId();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //判断结果是0
        int r=result.intValue();

        if(r!=0){
            //不为0，没有购买资格
            return Result.fail(r==1 ? "库存不足":"不能重复下单");
        }
        //为0，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        //创建订单
        //添加订单id，用户id，代金券id
        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //保存阻塞队列
        orderTasks.add(voucherOrder);

        //返回订单id
        return Result.ok(orderId);
    }

    //原生秒杀
// @Override
//    //事务不能加在这个方法上，会导致这个事务提交之前锁被释放从而导致并发问题
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券信息
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//
//        //判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        //判断库存是否充足
//        if(voucher.getStock()<1){
//            return Result.fail("库存不足");
//        }
//
//        Long userId= UserHolder.getUser().getId();
//        //synchronized (userId.toString().intern()) {
//        //获取锁（分布式）
//        //创建锁对象
//        //final SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        //获取锁
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            //获取锁失败
//            return Result.fail("重复下单");
//        }
//        try{
//            return voucherOrderService.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//        //}
//    }

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
            if(count>0){
                return Result.fail("用户已经购买过一次");
            }

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

    @Transactional
    //事务失效:直接使用this调用，spring事务是使用代理对象实现
    //处理方式，自己调用自己
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //一人一单
        Long userId= voucherOrder.getId();

        //查询订单
        int count= query()
                .eq("user_id",userId)
                .eq("voucher_id",voucherOrder.getVoucherId())
                .count();
        if(count>0){
            log.error("用户已经购买过一次");
            return;
        }

        //扣减库存
        final boolean success = iSeckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)
                .update();
        if(!success){
            log.error("库存不足");
        }


        save(voucherOrder);

    }
}
