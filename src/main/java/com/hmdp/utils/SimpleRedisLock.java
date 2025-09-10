package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;

    private final String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-"; // 使用UUID生成一个随机的UUID字符串 true去掉横线
    // 看RedisScript类层级的快捷键是Ctrl+H ,ctrl+shift+u把unlock_script全变大写字母或小写字母
    // 类一加载就会初始化静态代码块，提高性能，不用每次都调用脚本，避免多次读取IO流
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 有空指针风险，需要判断
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用lua脚本来保证原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

    }

  /*  public void unlock() {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 判断标识是否一致
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (threadId.equals(id)){
            // 这里在极端情况下(在判断完是自己的锁之后准备删除锁之前)也会发生阻塞，因为jvm的垃圾回收机制导致当前线程的阻塞，然后锁超时被删除了，此时另一个线程就拿到锁了，
            // 然后此时当前线程不阻塞了，醒过来之后就把另一个拿到锁的线程的锁给删除了，这就又导致了锁的误删，所以要用lua脚本来保证原子性

            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }

    }*/
}
