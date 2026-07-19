package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followId,Boolean isFollow){
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //判断是取关还是关注
        if(isFollow){
            //关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followId);
            boolean save = save(follow);
            //存入redis中
            if(save){
                stringRedisTemplate.opsForSet().add(key,followId.toString());
            }
        }
        else{//取关
            boolean remove = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followId));
            //移除redis
            if(remove) {
                stringRedisTemplate.opsForSet().remove(key, followId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId){
        //获取用户id
        Long userId = UserHolder.getUser().getId();

        //查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id){
        //1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return Result.fail("失败");
        }
        Long userId = user.getId();
        String key = "follows:" + userId;

        //2.求交集
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key,key2);

        //3.解析集合
        List<Long >ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(use -> BeanUtil.copyProperties(use, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }
}
