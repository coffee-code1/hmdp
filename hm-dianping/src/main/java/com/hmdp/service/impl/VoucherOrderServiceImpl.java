package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long id) {
        //查询当前这个秒杀券的库存
        SeckillVoucher voucher = seckillVoucherService.getById(id);
        //判断是否开始，注意这里要是在当前之后会返回false
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //判断是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //查询库存是否剩余
        if(voucher.getStock() < 1){
            return Result.fail("库存见底");
        }
        //单系统锁
//        Long userid = UserHolder.getUser().getId();
//        synchronized (userid.toString().intern()){
//            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(id);
//        }
        //分布式锁
        Long userId = UserHolder.getUser().getId();
       SimpleRedisLock simplelock = new SimpleRedisLock(stringRedisTemplate,"Order:"+userId);
       boolean success = simplelock.tryLock(12000L);//ttl时间
       if(!success){
           return Result.fail("一人只能下一单");
       }

        try {
            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(id);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            simplelock.unlock();
        }
    }
    @Transactional
    public Result createVoucherOrder(Long id){
        //剩余调用mp修改数据库
        boolean success = seckillVoucherService
                .update().setSql("stock = stock - 1")
                .eq("voucher_id",id)
                .gt("stock",0)//判断是否大于0，乐观锁
                .update();
        if(!success){
            return Result.fail("修改失败");
        }
        //生成订单，返回订单号
        VoucherOrder voucherOrder = new VoucherOrder();
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //设置用户id
        voucherOrder.setUserId(userId);
        //设置秒杀券id
        voucherOrder.setVoucherId(id);
        //获取订单id
        Long orderId = redisIDWorker.nextId("order");
        //设置订单id
        voucherOrder.setId(orderId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
