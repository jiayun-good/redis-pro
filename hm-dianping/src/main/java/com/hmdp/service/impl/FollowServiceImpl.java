package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "BLOG:LIKED:" + userId;
        //关注还是取消关注操作
        if(isFollow){
            //关注-向数据表添加关注人
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean save = save(follow);
            //存入缓存
            if(save){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }

        }else {
            //取消关注
            QueryWrapper<Follow> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id",userId)
                    .eq("follow_user_id",followUserId);
            boolean remove = remove(wrapper);
            if(remove){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //查询是否关注了-查询数据表是否有该用户
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();

        return Result.ok(count>0);
    }

    @Override
    public Result getCommonById(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key1 = "BLOG:LIKED:" + userId;
        String key2 = "BLOG:LIKED:" + followUserId;
        Set<String> commonFollowIds = stringRedisTemplate.opsForSet().intersect(key1,key2);
        List<Long> ids = commonFollowIds.stream()
                .map(item -> Long.valueOf(item))
                .collect(Collectors.toList());

        if(ids.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<UserDTO> commonUserDTO = userService.listByIds(ids)
                .stream().map(item -> BeanUtil.toBean(item, UserDTO.class))
                .collect(Collectors.toList());

        System.out.println("key1=" + key1);
        System.out.println("key2=" + key2);

        System.out.println("set1=" + stringRedisTemplate.opsForSet().members(key1));
        System.out.println("set2=" + stringRedisTemplate.opsForSet().members(key2));
        return Result.ok(commonUserDTO);
    }
}
