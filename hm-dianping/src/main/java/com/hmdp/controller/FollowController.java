package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService followService;
    //关注/取消关注 操作
    @PutMapping("/{followUserId}/{isFollow}")
    public Result follow(@PathVariable("followUserId") Long followUserId,
                         @PathVariable("isFollow") Boolean isFollow){
        return followService.follow(followUserId,isFollow);
    }

    //查询是否关注
    @GetMapping("/or/not/{followUserId}")
    public Result isFollow(@PathVariable("followUserId")Long followUserId){
        return  followService.isFollow(followUserId);
    }

    //查询共同关注
    @GetMapping("/common/{followUserId}")
    public Result getCommonById(@PathVariable("followUserId") Long followUserId){
        return followService.getCommonById(followUserId);
    }
}
