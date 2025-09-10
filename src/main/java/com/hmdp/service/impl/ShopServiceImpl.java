package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.exception.CacheRebuildTimeoutException;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.util.stream.DoubleStream.builder;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // log.info("===== 开始查询商铺，ID: {} =====", id);
        //
        // // 临时预热操作：确保正确的键名下有数据
        // try {
        //     saveShop2Redis(id, 20L);
        //     log.info("===== 预热成功，ID: {} =====", id);
        // } catch (Exception e) {
        //     log.error("===== 预热失败，ID: {} =====", id, e);
        // }

        // 解决缓存穿透
        // Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_NULL_TTL,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient.queryWithMutex(RedisConstants.CACHE_SHOP_KEY,RedisConstants.LOCK_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.MINUTES);
        if (shop == null) {
            log.warn("===== 商铺查询失败，ID: {} =====", id);
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    // 封装 get 操作
    private String getShopCache(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    // 线程池
    // private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // public Shop queryWithLogicalExpire(Long id) {
    //     // 商品缓存key
    //     String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
    //     // 1.从redis查询商铺缓存
    //     String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
    //     // 2.判断是否存在
    //     if (StringUtils.isBlank(shopJson)) {
    //         // 3.不存在，返回数据
    //         return null;
    //     }
    //     // 4.命中，需要把json反序列化成对象
    //     RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
    //     Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
    //     LocalDateTime expireTime = redisData.getExpireTime();
    //     // 5.判断是否过期
    //     if (expireTime.isAfter(LocalDateTime.now())) {
    //         // 5.1 未过期，直接返回店铺信息
    //         return shop;
    //     }
    //     // 5.2 已过期，需要缓存重建
    //     // 6.缓存重建
    //     // 6.1获取互斥锁
    //     String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
    //     // 6.2判断是否获取锁成功
    //     if (tryLock(lockKey)) {
    //         // 6.3获取锁成功且再判断一次缓存过期时间，防止已经重建好的缓存且锁刚被删除时又有个线程拿到锁来重复创建缓存
    //         try {
    //             // 双重检查：再次验证是否过期（防止重复重建缓存）
    //             String latestJson = stringRedisTemplate.opsForValue().get(shopKey);
    //             if (StringUtils.isNotBlank(latestJson)) {
    //                 RedisData latestData = JSONUtil.toBean(latestJson, RedisData.class);
    //                 if (latestData.getExpireTime().isAfter(LocalDateTime.now())) {
    //                     //当发现缓存未过期时，应该提前释放锁，然后返回数据
    //                     unLock(lockKey);
    //                     // 已经重建完缓存，直接返回数据
    //                     return JSONUtil.toBean((JSONObject) latestData.getData(), Shop.class);
    //                 }
    //             }
    //             // 确实需要重建，启动异步重建
    //             rebuildShopCache(id, lockKey);
    //         } catch (Exception e) {
    //             unLock(lockKey);
    //             throw new RuntimeException(e);
    //         }
    //     }
    //     // 8.返回商铺信息
    //     return shop;
    // }

    // 实现独立线程的缓存重建方法
    // public void rebuildShopCache(Long id, String lockKey) {
    //     CACHE_REBUILD_EXECUTOR.submit(() -> {
    //         // 7.5重建缓存
    //         try {
    //             this.saveShop2Redis(id, 20L);
    //             // 模拟耗时
    //             Thread.sleep(200);
    //         } catch (Exception e) {
    //             throw new RuntimeException(e);
    //         } finally {
    //             // 释放锁
    //             unLock(lockKey);
    //         }
    //     });
    // }

    // public Shop queryWithMutex(Long id) {
    //     // 商品缓存key
    //     String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
    //     // 1.从redis查询商铺缓存
    //     String shopJson = getShopCache(shopKey);
    //     // 2.判断是否存在
    //     if (StringUtils.isNoneBlank(shopJson)) {
    //         // 3.存在，返回数据
    //         return JSONUtil.toBean(shopJson, Shop.class);
    //     }
    //     // 2. 命中空值
    //     if (shopJson != null && shopJson.isEmpty()) {
    //         // 返回一个错误信息
    //         return null;
    //     }
    //     // 4.实现缓存重建
    //     // 4.1获取互斥锁
    //     String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
    //     Shop shop = null;
    //     // 获取锁是否成功
    //     boolean isLock = tryLock(lockKey);
    //     // 重试次数
    //     int retryCount = 0;
    //     // 最大循环次数
    //     final int maxRetryCount = 25;
    //     try {
    //         // 分支1：没抢到锁 → 等待并轮询缓存
    //         // 4.2判断锁是否获取成功
    //         if (!isLock) {
    //             // 4.3获取锁失败
    //             while (retryCount < maxRetryCount) {
    //                 // 公式分解：
    //                 // - retryCount / 5 ：每5次重试增加一个等级
    //                 // - 1 + retryCount / 5 ：基础倍数
    //                 // - 20L * (基础倍数) ：最终睡眠时间
    //                 Thread.sleep(20L * (1 + retryCount / 5)); // 动态调整间隔
    //                 shopJson = getShopCache(shopKey);
    //                 if (StringUtils.isNotBlank(shopJson)) {
    //                     return JSONUtil.toBean(shopJson, Shop.class);
    //                 }
    //                 if (shopJson != null && shopJson.isEmpty()) {
    //                     return null;
    //                 }
    //                 retryCount++;
    //             }
    //             // 没有获得锁线程执行到这里说明获得锁的线程既没有重建缓存，又不是""字符串，只能是以下情况了
    //             // ❌ 场景 1：重建线程卡住或崩溃
    //             // ❌ 场景 2：重建线程抛出异常，未写缓存
    //             // ❌ 场景 3：Redis 写入失败（网络抖动）
    //             // 超时 → 区分“不存在”和“系统异常”
    //             log.warn("等待缓存重建超时，shopId={}，可能原因：重建线程异常或Redis写入失败", id);
    //             throw new CacheRebuildTimeoutException("缓存重建超时，请检查DB或Redis状态");
    //         }
    //         // ✅ 只有抢到锁的线程才走到这里
    //         // 4.4成功，根据id查询数据库
    //         shop = getById(id);
    //         // 模拟重建的延时
    //         // Thread.sleep(200);
    //         // 5.数据不存在，返回错误
    //         if (ObjectUtils.isEmpty(shop)) {
    //             // 将空值写入redis，防止缓存穿透
    //             stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    //             // 返回错误信息
    //             return null;
    //         }
    //         // 6.存在写入redis缓存
    //         stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL,
    //                 TimeUnit.MINUTES);
    //     } catch (InterruptedException e) {
    //         throw new RuntimeException(e);
    //     } finally {
    //         // 7.释放互斥锁
    //         // ✅ 守护式释放：只有加锁者才能解锁
    //         if (isLock) {
    //             unLock(lockKey);
    //         }
    //     }
    //     // 8.返回
    //     return shop;
    // }

    // public Shop queryWithPassThrough(Long id) {
    //     // 商品缓存key
    //     String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
    //     // 1.从redis查询商铺缓存
    //     String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
    //     // 2.判断是否存在
    //     if (StringUtils.isNoneBlank(shopJson)) {
    //         // 3.存在，返回数据
    //         return JSONUtil.toBean(shopJson, Shop.class);
    //     }
    //     // 2. 命中空值
    //     if (shopJson != null && shopJson.isEmpty()) {
    //         return null;
    //     }
    //     // 4.不存在，根据id查询数据库
    //     Shop shop = getById(id);
    //     // 5.数据不存在，返回错误
    //     if (ObjectUtils.isEmpty(shop)) {
    //         // 将空值写入redis，防止缓存穿透
    //         stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    //         // 返回错误信息
    //         return null;
    //     }
    //     // 6.存在写入redis缓存
    //     stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL,
    //             TimeUnit.MINUTES);
    //     // 7.返回
    //     return shop;
    // }

    private boolean tryLock(String key) { // setIfAbsent： 如果不存在就创建 ;Absent:缺席，不存在
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS);
        return BooleanUtils.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 缓存预热
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 模拟重建的延时
        Thread.sleep(200);
        // 2.封装逻辑过期时间
        RedisData<Shop> redisData = RedisData.<Shop>builder()
                .data(shop)
                .expireTime(LocalDateTime.now().plusSeconds(expireSeconds)).build();
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Result update(Shop shop) {
        Long shopId = shop.getId();
        if (ObjectUtils.isEmpty(shopId)) {
            return Result.fail("店铺id不能为空！");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要做坐标查询，根据数据库查询
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3.查询redis，按照距离排序、分页，结果：shopId、distance(距离)
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // geosearch bylonlat x y byradius 10 withdistance
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id 查询shop
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        // 4.1 截取 from - end 的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 6.返回
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id, " + StringUtils.join(ids, ",") + ")").list();
        for (Shop shop : shops) {
           shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
