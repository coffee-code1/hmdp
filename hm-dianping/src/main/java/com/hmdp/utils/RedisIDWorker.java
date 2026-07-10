package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIDWorker {
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BASE = 32;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyprefix){
        //获取现在的时间
        LocalDateTime now  = LocalDateTime.now();
        //转化成秒
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //计算间隔
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //生成序列化
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyprefix+":"+date);
        return timestamp << COUNT_BASE | count;
    }
}
