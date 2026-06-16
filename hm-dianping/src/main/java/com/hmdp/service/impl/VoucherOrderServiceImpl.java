package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
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
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Transactional

    @Override
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
        if(seckillVoucher.getStock()<0){
            return Result.fail("库存不足！");
        }
        //5.扣减库存
        Boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id",voucherId)
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
    }
}
