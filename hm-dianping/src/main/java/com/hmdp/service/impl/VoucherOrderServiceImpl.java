package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    // 懒加载解决循环依赖问题
    @Lazy
    @Autowired
    private IVoucherOrderService self; //注入自身代理对象

    //秒杀脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    /*阻塞队列-把订单放入这个队列,真正的下单处理由后台线程慢慢消费*/
    /*private BlockingQueue<VoucherOrder> ordersTasks = new ArrayBlockingQueue<>(1024*1024);*/
    /*单线程线程池,专门用来处理秒杀订单*/
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    /*单线程池消费*/
    /*private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    // 空时挂起，队列中有值才会继续执行
                    VoucherOrder voucherOrder = ordersTasks.take();
                    handleVoucherOrder(voucherOrder);
                }catch (Exception e){
                    log.error("订单处理异常",e);
                }
            }
        }
    }*/

    public static String queueName = "stream.order";
    //基于stream消息队列
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //1.从 Redis Stream 消息队列中拉取订单消息
                    // XREADGROUP GROUP gI c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "v1"),  // ① 消费者组和消费者名
                            StreamReadOptions.empty() // ② 读取选项
                                    .count(1) // 每次取1条
                                    .block(Duration.ofSeconds(2)), // 阻塞2秒
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()) // ③ 读取位置
                    );
                    //2.判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        continue;
                    }
                    //3.解析消息中的订单
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.toBean(value, VoucherOrder.class);
                    //4.下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                }catch (Exception e){
                    log.error("订单处理异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //1.获取 pengdingList 中的订单消息 XREADGRoUP GRoUP gl cI COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "v1"),  // ① 消费者组和消费者名
                            StreamReadOptions.empty() // ② 读取选项
                                    .count(1), // 每次取1条
                            StreamOffset.create(queueName, ReadOffset.from("0")) // ③ 读取位置
                    );
                    //2.判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        break;
                    }
                    //3.解析消息中的订单
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.toBean(value, VoucherOrder.class);
                    //4.下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                }catch (Exception e) {
                    log.error("Pending 消息处理异常", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        // 当线程被中断时，sleep() 会抛出 InterruptedException
                        // ⚠️ 关键：抛出异常后，中断标志位会被自动清除（重置为 false）
                        Thread.currentThread().interrupt();  // 重新设置中断标志为 true
                        break;
                    }
                    // 继续循环
                }
            }
        }
    }
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        //生成UUID的订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate
                .execute(SECKILL_SCRIPT, Collections.emptyList(),voucherId.toString(),userId.toString(),String.valueOf(orderId));
        if(result.intValue() !=0){
            return Result.fail(result.intValue() == 1 ? "库存不足":"不能重复下单");
        }

        //2.为0
        return Result.ok(orderId);
    }

    //原下单方法seckillVoucher
    /*@Override
    public Result seckillVoucher(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate
                .execute(SECKILL_SCRIPT, Collections.emptyList(),voucherId.toString(),userId.toString());
        if(result.intValue() !=0){
            return Result.fail(result.intValue() == 1 ? "库存不足":"不能重复下单");
        }
        //2.为0，把下单信息存入阻塞队列
        //2.1 生成UUID的订单id
        long orderId = redisIdWorker.nextId("order");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 获取代理对象，让 @Transactional 生效
        //proxy = (IVoucherOrderService) AopContext.currentProxy();
        //2.2 放入阻塞队列
        ordersTasks.add(voucherOrder);

        return Result.ok(orderId);
    }*/

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1.查优惠券
        SeckillVoucher  seckillVoucher = seckillVoucherService.getById(voucherId); //操作tb_seckill_voucher表
        LocalDateTime now = LocalDateTime.now();
        //2.判断秒杀是否开始
        if(now.isBefore(seckillVoucher.getBeginTime())){
            return Result.fail("秒杀还未开始！");
        }
        // 3.判断秒杀是否结束
        if (now.isAfter(seckillVoucher.getEndTime())) {
            // 秒杀已经结束
            return Result.fail("秒杀已经结束！");
        }
        //4.判断库存是否充足
        if(seckillVoucher.getStock() <= 0){
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();

        //获取锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        Boolean isLock = lock.tryLock();
        if(!isLock){
            return Result.fail("不允许重复下单！");
        }
        try {
            return self.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
//        synchronized(userId.toString().intern()){
//            //synchronized: JVM 本地锁
//            return self.createVoucherOrder(voucherId);
//        }

    }*/

    //负责加分布式锁，保证一人一单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 用 Redisson 实现分布式锁，防止一人多单
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            //proxy.createVoucherOrder(voucherOrder);
            self.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count > 0){
            //return Result.fail("该用户已经购买过一次！");
            log.error("该用户已经购买过一次！");
            return;
        }
        //5.扣减库存
        Boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                //.eq("stock",seckillVoucher.getStock()) //用乐观锁的CAS
                .ge("stock",1)
                .update();
        if(!success){
            //return Result.fail("库存不足！");
            log.error("库存不足！");
            return;
        }

        //6.存入订单表
        save(voucherOrder); //操作tb_voucher_order表
        //return Result.ok(voucherOrder);

    }

    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            return Result.fail("该用户已经购买过一次！");
        }
        //5.扣减库存
        Boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                //.eq("stock",seckillVoucher.getStock()) //用乐观锁的CAS
                .ge("stock",1)
                .update();
        if(!success){
            return Result.fail("库存不足！");
        }
        //6.创建订单（订单id，用户id，代金券id必填）
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        //7.存入订单表
        save(voucherOrder); //操作tb_voucher_order表
        return Result.ok(orderId);

    }*/
}
