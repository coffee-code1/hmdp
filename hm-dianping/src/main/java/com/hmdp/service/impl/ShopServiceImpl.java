package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.CACHE_SHOP_KEY;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
     //缓存穿透
      //  Shop shop=queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        return Result.ok(shop);
    }
    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 第一层缓存检查
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 缓存空值，防止穿透
        if(shopJson != null){
            return null;
        }

        String lockkey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockkey);
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 第二层DoubleCheck核心
            String cacheShop = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(cacheShop)){
                unlock(lockkey); // 手动解锁
                return JSONUtil.toBean(cacheShop, Shop.class);
            }

            // 查库
            shop = getById(id);
            if(shop == null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                unlock(lockkey); // 手动解锁
                return null;
            }
            // 回填缓存
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockkey);
        }
        return shop;
    }
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis里查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //查到了，转成bean返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //判断是不是空值
        if(shopJson != null){
            return null;
        }
        //不存在，根据id查询数据库
        Shop shop = getById(id);

        //不存在数据库返回错误
        if(shop == null){
            //插入空值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
    @Override
    @Transactional
    public Result update(Shop shop){
        //1.更新数据库
        updateById(shop);
        //2.更新缓存
        Long id=shop.getId();
        if(id == null){
            return Result.fail("店铺的id不能为空");
        }
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
