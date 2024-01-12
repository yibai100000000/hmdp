package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    public RedisIdWorker redisIdWorker;

    private ExecutorService es= Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch=new CountDownLatch(300);

        Runnable task= ()->{
            for(int i=0;i<100;i++){
                long id=redisIdWorker.nextId("testOrder");
                System.out.println("id:"+id);
            }
            countDownLatch.countDown();
        };

        final long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
          es.submit(task);
        }
        countDownLatch.await();
        final long end = System.currentTimeMillis();

        System.out.println(end-begin);
    }


}
