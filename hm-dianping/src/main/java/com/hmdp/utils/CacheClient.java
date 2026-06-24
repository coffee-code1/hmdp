package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //定义传入任何的数据类型都能以字符串写入redis
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //定义逻辑过期时间
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //所谓的逻辑过期时间就是再当前时间加上设置的固定时间，存入数据中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //解决缓存穿透
    public <T, ID> T queryWithPassThrough(
            String keyPrefix, ID id, Class<T> type, Function<ID, T> dbcallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis里查询
        String Json = stringRedisTemplate.opsForValue().get(key);

        //查到了，转成bean返回
        if (StrUtil.isNotBlank(Json)) {
            T t = JSONUtil.toBean(Json, type);
            return t;
        }
        //判断是不是空值
        if (Json != null) {
            return null;
        }
        //不存在，根据id查询数据库
        T t = dbcallBack.apply(id);

        //不存在数据库返回错误
        if (t == null) {
            //插入空值
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(t), time, unit);
        return t;
    }

    //解决缓存击穿
    public <T, ID> T queryWithLogicExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<T> type,
            Function<ID, T> dbCallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //没查到就代表数据不合法，直接返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }

        //将查到的字符串反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //这里先利用parseObj转成JsonObject再转成bean
        T t = JSONUtil.toBean(JSONUtil.parseObj(redisData.getData()), type);
        //判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            //不过期直接返回查询的数据
            return t;
        }

        //过期了，那就获取互斥锁
        String lockKey = lockKeyPrefix + id;
        //判断是否获取成功
        if (tryLock(lockKey)) {
            //成功了，利用线程池开启新的线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    T freshData = dbCallback.apply(id);
                    //这里简化了逻辑，不考虑数据库查不到的情况，认为缓存中没有的数据库也没有
                    setWithLogicExpire(key, freshData, time, unit);
                } catch (Exception e) {
                    log.error("rebuild cache failed, key={}", key, e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return t;
    }

    private boolean tryLock(String key) {
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
