package cc;

import org.springframework.data.redis.core.StringRedisTemplate;

public class test {

    private StringRedisTemplate stringRedisTemplate;

   public void a(){
       stringRedisTemplate.opsForValue()
   }
}
