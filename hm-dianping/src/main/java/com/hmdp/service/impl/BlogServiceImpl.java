package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.dto.ScrollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("笔记上传失败");
        }
        //查询粉丝
        List<Follow> userId = followService.query().eq("follow_user_id", user.getId()).list();
        //推送给粉丝
        for(Follow follow : userId){
            //获取粉丝id
            Long followId = follow.getUserId();
            //将文章id放入粉丝的set收件箱中
            String key = "feed:" + followId;
            stringRedisTemplate.opsForZSet()
                    .add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryHotBlog(Integer current){
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            //查询是否被点赞
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id){
        //查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        //封装用户
        queryBlogUser(blog);

        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id){
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //判断是否点赞
        String key = BLOG_LIKED_KEY + id;

        //如果没有点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //数据库更新
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id",id).update();
            //成功后加入缓存
            if (!isSuccess) {
                return Result.fail("笔记不存在");
            }
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }//点赞了
        else {
            //数据库更新减一
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update();
            //成功后移除缓存
            if (!isSuccess) {
                return Result.fail("笔记不存在");
            }
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        Map<Long, User> users = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<UserDTO> userDTOs = userIds.stream()
                .map(users::get)
                .filter(user -> user != null)
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }

    public Result queryBlogOfFollow(Long max,Integer offset){
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查询收件箱
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //4.解析数据
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        //表示跳过的查询数据
        int os = 1;
        for(ZSetOperations.TypedTuple<String> tuple:typedTuples){
            //获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //获取分数（时间戳），转成long
            long time = tuple.getScore().longValue();
            //查到相同的就跳过，更新下一次跳过时间
            if(time == minTime){
                os++;
            }
            else{
                //因为是按照顺序取出
                minTime = time;
                //跳过当前
                os = 1;
            }
            if (ids.size() < typedTuples.size()) {
                continue;
            }
            //根据id查询blog,直接listByIds(ids)查询的顺序不对。与上面共同关注一样
            Map<Long, Blog> blogs = listByIds(ids).stream()
                    .collect(Collectors.toMap(Blog::getId, Function.identity()));
            
            //返回
            List<Blog> blogList = ids.stream()
                    .map(blogs::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            blogList.forEach(blog -> {
                //查询博主，展示出来
                queryBlogUser(blog);
                //查询是否关注
                isBlogLiked(blog);
            });

            //封装返回
            ScrollResult result = new ScrollResult();
            result.setList(blogList);
            result.setMinTime(minTime);
            result.setOffset(os);
            return Result.ok(result);
        }
        return Result.ok();
    }
    //抽离出的私有方法用来查询该博客下的用户
    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }

    private void isBlogLiked(Blog blog){
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            blog.setIsLike(false);
            return;
        }
        Long userId = user.getId();
        //判断是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForZSet().score(key, userId.toString()) != null;
        //如果点赞了就设置isLike为true
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }

}
