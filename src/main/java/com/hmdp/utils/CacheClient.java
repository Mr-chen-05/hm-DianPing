package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData<Object> redisData = RedisData.<Object>builder()
                .data(value)
                .expireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time))).build();
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }

    // 缓存击穿
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                          Long cacheNullTtl, Long time, TimeUnit unit) {
        // 商品缓存key
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StringUtils.isNoneBlank(json)) {
            // 3.存在，返回数据
            return JSONUtil.toBean(json, type);
        }
        // 2. 命中空值
        if (json != null && json.isEmpty()) {
            // 返回一个错误信息
            return null;
        }
        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.数据不存在，返回错误
        if (Objects.isNull(r)) {
            // 将空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", cacheNullTtl, unit);
            // 返回错误信息
            return null;
        }
        // 6.存在写入redis缓存
        this.set(key, r, time, unit);
        // 7.返回
        return r;
    }

    // 互斥锁解决缓存击穿
    public <R, ID> R queryWithMutex(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type,
                                    Function<ID, R> dbFallback, Long time, Long cacheNullTtl, TimeUnit unit) {
        // 缓存key
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StringUtils.isNoneBlank(json)) {
            // 3.存在，返回数据
            return JSONUtil.toBean(json, type);
        }
        // 2. 命中空值
        if (json != null && json.isEmpty()) {
            // 返回一个错误信息
            return null;
        }
        // 4.实现缓存重建
        // 4.1获取互斥锁
        String lockKey = lockKeyPrefix + id;
        R r = null;
        // 获取锁是否成功
        boolean isLock = tryLock(lockKey);
        // 重试次数
        int retryCount = 0;
        // 最大循环次数
        final int maxRetryCount = 25;
        try {
            // 分支1：没抢到锁 → 等待并轮询缓存
            // 4.2判断锁是否获取成功
            if (!isLock) {
                // 4.3获取锁失败
                while (retryCount < maxRetryCount) {
                    // 公式分解：
                    // - retryCount / 5 ：每5次重试增加一个等级
                    // - 1 + retryCount / 5 ：基础倍数
                    // - 20L * (基础倍数) ：最终睡眠时间
                    Thread.sleep(20L * (1 + retryCount / 5)); // 动态调整间隔
                    json = stringRedisTemplate.opsForValue().get(key);
                    if (StringUtils.isNotBlank(json)) {
                        return JSONUtil.toBean(json, type);
                    }
                    if (json != null && json.isEmpty()) {
                        return null;
                    }
                    retryCount++;
                }
                // 没有获得锁线程执行到这里说明获得锁的线程既没有重建缓存，又不是""字符串，只能是以下情况了
                // ❌ 场景 1：重建线程卡住或崩溃
                // ❌ 场景 2：重建线程抛出异常，未写缓存
                // ❌ 场景 3：Redis 写入失败（网络抖动）
                // 超时 → 区分“不存在”和“系统异常”
                log.warn("等待缓存重建超时，id={}，可能原因：重建线程异常或Redis写入失败", id);
                throw new RuntimeException("缓存重建超时，请检查DB或Redis状态");
            }
            // ✅ 只有抢到锁的线程才走到这里
            // 4.4成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.数据不存在，返回错误
            if (ObjectUtils.isEmpty(r)) {
                // 将空值写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", cacheNullTtl, unit);
                // 返回错误信息
                return null;
            }
            // 6.存在写入redis缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time,
                    unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            // ✅ 守护式释放：只有加锁者才能解锁
            if (isLock) {
                unLock(lockKey);
            }
        }
        // 8.返回
        return r;
    }

    // 线程池
    // 使用自定义线程池配置
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            5,  // 核心线程数：常驻线程
            20,  // 最大线程数：高峰期最多线程
            60L, TimeUnit.SECONDS, // 空闲时间：非核心线程存活时间
            new LinkedBlockingQueue<>(100), // 队列大小：等待队列容量
            new ThreadFactory() { // 线程工厂：自定义线程创建
                private final AtomicInteger threadNumber = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "cache-rebuild-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者执行
    );

    // 逻辑过期解决缓存击穿
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 商品缓存key
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StringUtils.isBlank(json)) {
            // 3.不存在，返回数据
            log.warn("===== 缓存数据不存在，返回null =====");
            return null;
        }
        // 4.命中，需要把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回店铺信息
            return r;
        }
        // 5.2 已过期，需要缓存重建
        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = lockKeyPrefix + id;
        // 6.2判断是否获取锁成功
        if (tryLock(lockKey)) {
            // 6.3获取锁成功且再判断一次缓存过期时间，防止已经重建好的缓存且锁刚被删除时又有个线程拿到锁来重复创建缓存
            try {
                // 双重检查：再次验证是否过期（防止重复重建缓存）
                String latestJson = stringRedisTemplate.opsForValue().get(key);
                if (StringUtils.isNotBlank(latestJson)) {
                    RedisData latestData = JSONUtil.toBean(latestJson, RedisData.class);
                    if (latestData.getExpireTime().isAfter(LocalDateTime.now())) {
                        // 当发现缓存未过期时，应该提前释放锁，然后返回数据
                        log.info("[{}] ✅ 双重检查发现缓存已更新且未过期，立即释放锁并返回新数据", UUID.randomUUID().toString(true)+"-"+Thread.currentThread().getId());
                        unLock(lockKey);
                        // 已经重建完缓存，直接返回数据
                        return JSONUtil.toBean((JSONObject) latestData.getData(), type);
                    }
                }
                // 确实需要重建，启动异步重建
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    // 7.5重建缓存
                    try {
                        // 重新查询数据库
                        R r1 = dbFallback.apply(id);
                        // 写入redis
                        this.setWithLogicalExpire(key, r1, time, unit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        // 释放锁
                        unLock(lockKey);
                    }
                });
            } catch (Exception e) {
                unLock(lockKey);
                throw new RuntimeException(e);
            }
        }
        // 8.返回旧信息
        return r;
    }

    private boolean tryLock(String key) { // setIfAbsent： 如果不存在就创建 ;Absent:缺席，不存在
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L,
                TimeUnit.SECONDS);
        return BooleanUtils.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    // ✅ 推荐的CacheClient优化版本
    public <R, ID> Map<ID, R> batchQueryOptimized(
            String keyPrefix, Collection<ID> ids, Class<R> type,
            Function<Collection<ID>, Map<ID, R>> dbFallback, Long time, TimeUnit unit) {

        // 1. 批量获取缓存（字符串方式）
        List<String> keys = ids.stream()
                .map(id -> keyPrefix + id)
                .collect(Collectors.toList());

        List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);

        // 2. 处理缓存命中和未命中
        Map<ID, R> result = new HashMap<>();
        List<ID> missedIds = new ArrayList<>();

        Iterator<ID> idIterator = ids.iterator();
        for (String value : values) {
            ID id = idIterator.next();
            if (StringUtils.isNotBlank(value)) {
                // 缓存命中：反序列化JSON
                result.put(id, JSONUtil.toBean(value, type));
            } else {
                // 缓存未命中：记录需要查数据库的ID
                missedIds.add(id);
            }
        }

        // 3. 批量写入缓存（Pipeline + 字符串）
        if (!missedIds.isEmpty()) {
            // 1. 批量查询数据库
            Map<ID, R> dbResult = dbFallback.apply(missedIds);
            // 2. Pipeline批量写入缓存
            stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (ID id : missedIds) {
                        String key = keyPrefix + id;
                        R data = dbResult.get(id);

                        if (data != null) {
                            // 🛡️ 防缓存雪崩：随机化TTL
                            // 🎯 字符串方式存储，Redis中可读
                            String value = JSONUtil.toJsonStr(data);
                            // TTL随机化算法
                            long randomTtl = time + (long) (Math.random() * time * 0.4 - time * 0.2);
                            // 假设原始TTL = 30分钟
                            // 随机范围：30 ± 6分钟 = 24-36分钟
                            // 避免大量缓存同时过期
                            Duration duration = Duration.of(randomTtl, unit.toChronoUnit());
                            operations.opsForValue().set(key, value, duration);
                            result.put(id, data);
                        } else {
                            // 🛡️ 防缓存穿透：缓存空值
                            operations.opsForValue().set(key, "", Duration.ofMinutes(2));
                        }
                    }
                    return null;
                }
            });
        }

        return result;
    }
    // 使用示例
    // Map<Long, Shop> result = cacheClient.batchQueryOptimized(
    //     "cache:shop:",                    // 参数1: keyPrefix
    //     Arrays.asList(1L, 2L, 3L),       // 参数2: ids
    //     Shop.class,                      // 参数3: type
    //     missedIds -> {                   // 参数4: dbFallback (Lambda)
    //         return shopService.listByIds(missedIds)
    //             .stream()
    //             .collect(Collectors.toMap(Shop::getId, shop -> shop));
    //     },
    //     30L,                            // 参数5: time
    //     TimeUnit.MINUTES                // 参数6: unit
    // );


    // public <R, ID> Map<ID, R> batchQueryOptimizedWithProtection(String keyPrefix, List<ID> ids,
    //                                                             Class<R> type, Function<List<ID>, Map<ID, R>> batchDbFallback,
    //                                                             Long time, Long cacheNullTtl, TimeUnit unit) {
    //     Map<ID, R> result = new HashMap<>();
    //
    //     // 1. 构建Redis keys
    //     List<String> keys = ids.stream()
    //             .map(id -> keyPrefix + id)
    //             .collect(Collectors.toList());
    //
    //     // 2. 批量从Redis获取
    //     List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
    //
    //     // 3. 解析结果，收集未命中的ID
    //     List<ID> missedIds = new ArrayList<>();
    //     for (int i = 0; i < ids.size(); i++) {
    //         String json = values.get(i);
    //         if (StringUtils.isNotBlank(json)) {
    //             result.put(ids.get(i), JSONUtil.toBean(json, type));
    //         } else if (json != null && json.isEmpty()) {
    //             // 🛡️ 防缓存穿透：命中空值，直接跳过
    //             // 空值表示数据库中不存在该记录
    //             continue;
    //         } else {
    //             // 缓存未命中，需要查询数据库
    //             missedIds.add(ids.get(i));
    //         }
    //     }
    //
    //     // 4. 批量查询数据库
    //     if (!missedIds.isEmpty()) {
    //         Map<ID, R> dbResult = batchDbFallback.apply(missedIds);
    //
    //         // 5. 批量写入Redis - 使用Pipeline
    //         stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    //             for (ID id : missedIds) {
    //                 String key = keyPrefix + id;
    //                 R data = dbResult.get(id);
    //
    //                 if (data != null) {
    //                     // 🛡️ 防缓存雪崩：随机化TTL (±20%)
    //                     long randomTtl = time + (long)(Math.random() * time * 0.4 - time * 0.2);
    //                     String value = JSONUtil.toJsonStr(data);
    //                     connection.setEx(key.getBytes(), randomTtl, value.getBytes());
    //                     // - setEx ：SET + EXPIRE的原子操作，设置值的同时设置过期时间
    //                     // - key.getBytes() ：将字符串转为字节数组，Redis底层存储格式
    //                     // - randomTtl ：过期时间（秒），支持随机化防雪崩
    //                     result.put(id, data);
    //                 } else {
    //                     // 🛡️ 防缓存穿透：缓存空值
    //                     connection.setEx(key.getBytes(), cacheNullTtl, "".getBytes());
    //                     // 不添加到result中，表示数据不存在
    //                 }
    //             }
    //             return null;
    //         });
    //     }
    //
    //     return result;
    // }
    // 📊 使用示例
    // // 批量查询商品，带防护机制
    // Map<Long, Shop> shops = cacheClient.batchQueryOptimizedWithProtection(
    //     "cache:shop:",           // 缓存前缀
    //     shopIds,                 // 商品ID列表
    //     Shop.class,              // 数据类型
    //     shopService::getByIds,   // 批量数据库查询
    //     30L,                     // 正常缓存30分钟
    //     2L,                      // 空值缓存2分钟
    //     TimeUnit.MINUTES
    // );

    public <R, ID> void warmUp(String keyPrefix, List<ID> ids,
                               Class<R> type, Function<ID, R> dbFallback,
                               Long time, TimeUnit unit) {
        CompletableFuture.allOf(
                ids.stream()
                        .map(id -> CompletableFuture.runAsync(() -> {
                            try {
                                R data = dbFallback.apply(id);
                                if (data != null) {
                                    setWithLogicalExpire(keyPrefix + id, data, time, unit);
                                }
                            } catch (Exception e) {
                                log.warn("缓存预热失败, id={}", id, e);
                            }

                        }, CACHE_REBUILD_EXECUTOR)).toArray(CompletableFuture[]::new)
        ).join();

    }
//     // 预热热门商品缓存
// List<Long> hotShopIds = Arrays.asList(1L, 2L, 3L, 4L, 5L);
// cacheClient.warmUp(
//     "cache:shop:",           // 缓存前缀
//     hotShopIds,              // 商品ID列表
//     Shop.class,              // 数据类型
//     shopService::getById,    // 数据库查询方法
//     30L,                     // 30秒逻辑过期
//     TimeUnit.SECONDS
// );

}
