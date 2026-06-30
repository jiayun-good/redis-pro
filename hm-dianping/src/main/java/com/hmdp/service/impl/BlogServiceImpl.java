package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IFollowService fllowService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result querBlogById(Long id) {
        // 查询用户
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }
    /*此博客是否已点赞*/
    private void isBlogLiked(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        String key = "blog:like" + blog.getId() ;
        //根据缓存添加liked字段
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
    /*查询博客作者信息*/
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        if (userId == null){
            return;
        }
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "blog:like" + id ;
        //判断是否点赞-是否存在于redis的Zset集合中（唯一不重复且排序集合）
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score != null){
            // 已经点赞，点赞缓存中清除该用户id,数据表-1
            stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            update().eq("id",id).setSql("liked = liked-1").update();
        } else{
            //未点赞，点赞缓存中新增该用户id，并带着时间戳,数据表+1
            stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            update().eq("id",id).setSql("liked = liked+1").update();
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = "blog:like" + id ;
        //查top5
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream()
                .map(item -> Long.valueOf(item))
                .collect(Collectors.toList());
        String idsStr = StrUtil.join("," , ids);

        List<UserDTO> userDTOS = userService.query()
                 // WHERE id IN (3, 1, 2) ORDER BY FIELD(id, 3,1,2)  , list()触发查询
                .in("id",ids).last("order by field(id," + idsStr + ")").list()
                .stream()
                .map(item -> BeanUtil.toBean(item, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBolg(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        boolean save = save(blog);
        // 3.存入缓存
        if(!save){
            return Result.fail("新增笔记失败！");
        }
        // 4.查询该笔记作者所有粉丝，然后向他们推送
        // 4.1去查谁关注了follow_user_id，也就是当前发博客的作者
        List<Follow> follows = fllowService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            //4.2获取粉丝id
            Long userId = follow.getUserId();
            //4.3向各粉丝推送-推到收件箱
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }



    /*获取关注列表,并且可以下拉分页*/
    @Override
    public Result followList(Long lastId,Integer offset) {
        //1.根据当前登录用户去收件箱查询关注的博主作品--ZREVRANGEBYSCORE key Max Min LIMIT offset count
        /*
        key	    feed:用户ID
        max	    最大 score
        min	    最小 score
        offset	跳过多少条
        count	查多少条
        * */
        Long userId = UserHolder.getUser().getId();
        String key = "feed:" + userId;
        //java API-reverseRangeByScoreWithScores(K key, double min, double max, long offset, long count)
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key,  0,lastId, offset, 2);

        if(typedTuples.isEmpty()){
            return Result.ok();
        }

        //2.1解析数据：blogId，时间戳，offset
        List ids = new ArrayList(typedTuples.size());
        //2.2记录最小时间
        long minTime = 0;
        //2.3记录最小值的个数
        int os = 1;
        //2.4整理需要的数据
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
                    /*typedTuples结构
                      value	blogId
                      score	时间戳（发布时间）
                    * */
            // 1. 获取 blogId
            String blogIdStr = tuple.getValue();
            ids.add(blogIdStr);
            // 2. 获取时间戳（score）
            long time = tuple.getScore().longValue();
            if(minTime == time){
                os++;
            }else {
                minTime = time;
                os = 1;
            }

        }

        //3.根据ids查询博客
        String idsStr = StrUtil.join("," , ids);
        //// WHERE id IN (3, 1, 2) ORDER BY FIELD(id, 3,1,2)  , list()触发查询
        List<Blog> blogs = query().in("id", ids).last("order by field(id," + idsStr + ")").list();
        //4.查询bolgs的每个博客的相关用户，是否被点赞
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        //整理返回的数据结构
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        //诉前端“下一次从哪里继续取
        return Result.ok(scrollResult);
    }


}
