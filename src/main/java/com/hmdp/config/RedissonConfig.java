package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        // 配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://192.168.100.129:6379")
                .setPassword("chenzhuo2005.");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
    // @Bean
    // public RedissonClient redissonClient2() {
    //     // 配置
    //     Config config = new Config();
    //     config.useSingleServer().setAddress("redis://192.168.100.129:6381").setPassword("chenzhuo2005.");
    //     // 创建RedissonClient对象
    //     return Redisson.create(config);
    // }
    // @Bean
    // public RedissonClient redissonClient3() {
    //     // 配置
    //     Config config = new Config();
    //     config.useSingleServer().setAddress("redis://192.168.100.129:6382").setPassword("chenzhuo2005.");
    //     // 创建RedissonClient对象
    //     return Redisson.create(config);
    // }

}
