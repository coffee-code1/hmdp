package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendcode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        String code = RandomUtil.randomNumbers(6);
        // 验证码直接写入 Redis，避免依赖 Session。
        stringRedisTemplate.opsForValue().set(
                LOGIN_CODE_KEY + phone,
                code,
                LOGIN_CODE_TTL,
                TimeUnit.MINUTES
        );
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginFormDTO) {
        String phone = loginFormDTO.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginFormDTO.getCode())) {
            return Result.fail("验证码错误");
        }

        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUser(phone);
            save(user);
        }

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String token = UUID.randomUUID().toString(true);

        // 将登录用户信息转成字符串 Hash，便于后续从 Redis 直接恢复登录态。
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())
        );

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    @Override
    public Result sign(){
        //获取用户id
        Long id = UserHolder.getUser().getId();
        LocalDateTime time = LocalDateTime.now();
        String keySuffix = time.format(DateTimeFormatter.ofPattern(":YYYYMM"));
        //key的形式为 sign:id:20233
        String key = USER_SIGN_KEY + id + keySuffix;
        //存入bitMap
        //当前日期号
        int day = time.getDayOfMonth();
        //这里的下标从一开始所以减一
        stringRedisTemplate.opsForValue().setBit(key,day-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount(){
        //获取用户id
        Long id = UserHolder.getUser().getId();
        LocalDateTime time = LocalDateTime.now();
        String keySuffix = time.format(DateTimeFormatter.ofPattern(":YYYYMM"));
        //key的形式为 sign:id:20233
        String key = USER_SIGN_KEY + id + keySuffix;
        //存入bitMap
        //当前日期号
        int day = time.getDayOfMonth();
        //获取截止到今天所有签到记录
        List<Long> longs = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0)
        );
        if(longs == null || longs.isEmpty()){
            //没有结果
            return Result.fail("没有抽签结果");
        }
        Long num = longs.get(0);
        if(num == 0|| num == null){
            return Result.ok(0);
        }
        int count = 0;
        while(true){
            //为0表示没签到
            if((num & 1) == 0){
                break;
            }else{
                count++;
            }
            num = num>>1;
        }
        return Result.ok(count);
    }
    private User createUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        return user;
    }
}
