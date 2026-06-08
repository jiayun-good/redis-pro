package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private  StringRedisTemplate stringRedisTemplate;


    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")  // 拦截所有请求
                .order(0);  // 优先级0，最先执行;//默认拦所有请求
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns("/shop/**","/shop-type/**","/shop/hot","/user/code","/user/login")
                .order(1);  // 优先级1，在RefreshTokenInterceptor之后执行;
    }
}
