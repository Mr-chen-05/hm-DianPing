package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.exception.BlogBusinessException;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.UserVO;
import com.hmdp.vo.mapper.UserVOMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private UserVOMapper userVOMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserMapper userMapper;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> BLOG_LIKED_SCRIPT;

    // shift + f6 同时修改变量
    static {
        BLOG_LIKED_SCRIPT = new DefaultRedisScript<>();
        BLOG_LIKED_SCRIPT.setLocation(new ClassPathResource("blogLiked.lua"));
        BLOG_LIKED_SCRIPT.setResultType(Long.class);
    }

    // 添加定时任务线程池
    private static final ScheduledExecutorService SCHEDULED_EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = true;

    @PostConstruct
    private void init() {
        // 原有的初始化代码
        try {
            redissonClient.getNodesGroup().pingAll();
            log.info("Redisson客户端连接正常");
        } catch (Exception e) {
            log.error("Redisson客户端连接异常", e);
        }

        // 启动初始化同步任务
        startInitialSync();

        // 启动定时同步任务（每分钟执行一次）
        startScheduledSync();
    }

    /**
     * 启动初始化同步任务
     */
    private void startInitialSync() {
        SCHEDULED_EXECUTOR.submit(() -> {
            try {
                log.info("开始执行初始化点赞数据同步...");
                syncLikeCountBetweenRedisAndDb();
                log.info("初始化点赞数据同步完成");
            } catch (Exception e) {
                log.error("初始化点赞数据同步失败", e);
            }
        });
    }

    /**
     * 启动定时同步任务
     */
    private void startScheduledSync() {
        // 初始延迟1分钟，之后每分钟执行一次
        SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                this::scheduledSyncTask,
                1,
                1,
                TimeUnit.MINUTES
        );
        log.info("每分钟定时同步任务已启动");
    }

    /**
     * 定时同步任务
     */
    private void scheduledSyncTask() {
        try {
            if (!running) {
                log.info("服务已停止，定时同步任务退出");
                return;
            }

            log.info("开始执行定时点赞数据同步 - {}",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            syncLikeCountBetweenRedisAndDb();

            log.info("定时点赞数据同步完成");
        } catch (Exception e) {
            log.error("定时同步任务执行异常", e);
        }
    }

    /**
     * 同步Redis和数据库的点赞数量
     */
    private void syncLikeCountBetweenRedisAndDb() {
        try {
            // 1. 获取Redis中的所有点赞计数
            Map<Object, Object> redisLikeCounts = stringRedisTemplate.opsForHash()
                    .entries(RedisConstants.BLOG_LIKED_COUNT_KEY);

            if (redisLikeCounts.isEmpty()) {
                log.info("Redis中暂无点赞计数数据");
                return;
            }

            log.info("开始同步 {} 个博客的点赞数据", redisLikeCounts.size());

            int updatedCount = 0;
            int consistentCount = 0;

            // 2. 遍历Redis中的每个博客点赞计数
            for (Map.Entry<Object, Object> entry : redisLikeCounts.entrySet()) {
                try {
                    Long blogId = Long.parseLong(entry.getKey().toString());
                    int redisLikeCount = Integer.parseInt(entry.getValue().toString());

                    // 3. 查询数据库中的点赞数
                    Blog blog = getById(blogId);
                    if (blog == null) {
                        log.warn("博客ID {} 在数据库中不存在，跳过同步", blogId);
                        continue;
                    }

                    int dbLikeCount = blog.getLiked();

                    // 4. 比较并同步
                    if (redisLikeCount != dbLikeCount) {
                        // 以Redis数据为准，更新数据库
                        boolean updateSuccess = update()
                                .set("liked", redisLikeCount)
                                .eq("id", blogId)
                                .update();

                        if (updateSuccess) {
                            updatedCount++;
                            log.debug("同步成功: 博客ID {} Redis:{} -> 数据库:{}",
                                    blogId, redisLikeCount, dbLikeCount);
                        } else {
                            log.error("同步失败: 博客ID {} 数据库更新失败", blogId);
                        }
                    } else {
                        consistentCount++;
                    }
                } catch (NumberFormatException e) {
                    log.warn("解析博客ID或点赞数失败: key={}, value={}",
                            entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    log.error("处理博客ID {} 时发生异常", entry.getKey(), e);
                }
            }

            log.info("同步完成: 更新 {} 个博客，{} 个博客数据一致", updatedCount, consistentCount);

        } catch (Exception e) {
            log.error("同步点赞数据时发生异常", e);
        }
    }

    @PreDestroy
    private void destroy() {
        log.info("开始关闭定时任务服务...");
        running = false;
        // 优雅关闭线程池
        shutdownExecutor(SCHEDULED_EXECUTOR, "定时任务");
        log.info("所有服务已关闭");
    }

    /**
     * 优雅关闭线程池
     */
    private void shutdownExecutor(ExecutorService executor, String executorName) {
        if (executor != null && !executor.isShutdown()) {
            log.info("开始关闭{}线程池...", executorName);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("{}线程池未能在10秒内正常关闭，强制关闭", executorName);
                    executor.shutdownNow();
                } else {
                    log.info("{}线程池已优雅关闭", executorName);
                }
            } catch (InterruptedException e) {
                log.warn("关闭{}线程池时被中断", executorName);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.setRealTimeLikeInfo(blog);
            // this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (ObjectUtils.isEmpty(blog)) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);
        // 2.1 从redis中获取实时点赞数量
        setRealTimeLikeInfo(blog);
        // 3.查询blog是否被点赞
        // isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void setRealTimeLikeInfo(Blog blog) {
        String blogLikedKey = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        String blogLikeCountKey = RedisConstants.BLOG_LIKED_COUNT_KEY;

        // 1. 获取实时点赞数量（优先从计数器获取）
        Object likeCountObj = stringRedisTemplate.opsForHash().get(blogLikeCountKey, blog.getId().toString());
        Integer likeCount = likeCountObj != null ? Integer.parseInt((String) likeCountObj) : null;

        blog.setLiked(likeCount);// 覆盖数据库的旧数据

        // 2. 获取当前用户点赞状态
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            Long userId = user.getId();
            Double score = stringRedisTemplate.opsForZSet().score(blogLikedKey, userId.toString());
            blog.setIsLike(score != null);
        } else {
            // 用户未登录，设置为未点赞状态
            blog.setIsLike(false);
        }
    }

    // private void isBlogLiked(Blog blog) {
    //     // 1.获取登录用户
    //     Long userId = UserHolder.getUser().getId();
    //     // 1.1获取缓存key
    //     String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
    //     Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
    //     blog.setIsLike(BooleanUtil.isTrue(isMember));
    // }

    private static final int MAX_RETRY_TIMES = 2;
    private static final long RETRY_DELAY_MS = 50;

    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 1.1获取缓存key
        String blogLikedKey = RedisConstants.BLOG_LIKED_KEY + id;
        String blogLikeCountKey = RedisConstants.BLOG_LIKED_COUNT_KEY; // 新增计数器key
        for (int attempt = 1; attempt <= MAX_RETRY_TIMES; attempt++) {
            try {
                Long result = stringRedisTemplate.execute(
                        BLOG_LIKED_SCRIPT,
                        Arrays.asList(blogLikedKey, blogLikeCountKey),
                        userId.toString(),
                        id.toString(),
                        String.valueOf(System.currentTimeMillis())
                );
                // 检查null
                if (result == null) {
                    log.warn("第{}次尝试：Lua脚本返回null", attempt);
                    if (attempt == MAX_RETRY_TIMES) {
                        return Result.fail("点赞操作失败，请稍后重试");
                    }
                    continue; // 继续重试
                }

                String isLiked = switch (result.intValue()) {
                    case 1 -> "成功点赞";
                    case -1 -> "取消点赞";
                    default -> {
                        log.error("Lua脚本返回异常值: {}", result);
                        yield "点赞状态异常"; // 返回错误消息字符串
                    }
                };

                // 根据消息判断成功还是失败
                if ("点赞状态异常".equals(isLiked)) {
                    return Result.fail(isLiked);
                } else {
                    return Result.ok(isLiked);
                }

            } catch (Exception e) {
                log.warn("点赞操作第{}次尝试失败，blogId: {}, userId: {}, error: {}",
                        attempt, id, userId, e.getMessage());

                if (attempt == MAX_RETRY_TIMES) {
                    log.error("点赞操作重试{}次后仍然失败", MAX_RETRY_TIMES, e);
                    return Result.fail("点赞操作失败，请稍后重试");
                }

                // 等待后重试
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Result.fail("点赞操作被中断");
                }
            }
        }

        return Result.fail("点赞操作失败");
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 1.查询top5点赞用户 zrange key 0-4
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中用户ids
        List<Long> ids = top5.stream().map(Long::parseLong).toList();
        String idsStr = StringUtils.join(ids, ",");
        // 3.根据用户id查询用户
        List<UserVO> userVOList = userMapper.selectByids(ids, idsStr).stream().map(user -> userVOMapper.entityToUserVO(user)).toList();
        // 4.返回
        return Result.ok(userVOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 发布的博客的用户id次数存入redis中
        stringRedisTemplate.opsForHash().increment(RedisConstants.BLOG_COUNTER_KEY + blog.getUserId(), "user_id",1);
        // 保存探店博文
        boolean save = save(blog);
        if (!save) {
            return Result.fail("发布失败");
        }
        // 查询所有博客主的所有粉丝
        List<Long> fansIdList = Objects.requireNonNull(stringRedisTemplate.opsForSet().members(RedisConstants.BLOG_FANS_KEY + user.getId())).stream().map(Long::parseLong).toList();
        if (fansIdList.isEmpty()){
            log.warn("该用户没有粉丝!博客id：{}", blog.getId().toString());
            return Result.ok("该用户没有粉丝!博客id："+blog.getId().toString());
        }
        fansIdList.forEach(fansId -> {
            // 把博文推送给粉丝 (粉丝 -> 博客id)
            stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + fansId, blog.getId().toString(), System.currentTimeMillis());
            // ✅ 每次推送都刷新 feed 的 TTL（比如 60 天）
            stringRedisTemplate.expire(RedisConstants.FEED_KEY + fansId, Duration.ofDays(60));
        });
        // 同时记录：博客id 被推给了谁 (博客id -> 多少粉丝id)
        String[] fanIdStrs = fansIdList.stream().map(String::valueOf).toArray(String[]::new);
        stringRedisTemplate.opsForSet().add(RedisConstants.BLOG_PUSH_FANS_KEY + blog.getId(), fanIdStrs);
        // ✅ 反向索引 TTL 设置短一些（7 天足够）
        stringRedisTemplate.expire(RedisConstants.BLOG_PUSH_FANS_KEY + blog.getId(), Duration.ofDays(7));
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查取收件箱 ZREVRANGE key Max Min LIMIT offset COUNT
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + userId, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok("您关注的博主暂无消息！");
        }
        // 4.解析数据：blogId, minTime(时间戳), offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        // 最后时间戳
        long minTime = 0;
        // 起始个数
        int ofs = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取blogId
            ids.add(Long.valueOf(Objects.requireNonNull(typedTuple.getValue())));
            // 获取时间戳
            long time = Objects.requireNonNull(typedTuple.getScore()).longValue();
            if (time == minTime){
                ofs++;
            }else {
                minTime = time;
                ofs = 1;
            }
        }
        // 5.根据id查询blog
        String idStr = StringUtils.join(ids, ",");
        List<Blog> blogList = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        blogList.forEach(blog -> {
            // 2.查询blog有关的用户
            queryBlogUser(blog);
            // 2.1 从redis中获取实时点赞数量
            setRealTimeLikeInfo(blog);
        });

        ScrollResult<Blog> blogScrollResult = new ScrollResult<>();
        blogScrollResult.setList(blogList);
        blogScrollResult.setMinTime(minTime);
        blogScrollResult.setOffset(ofs);
        // 6.封装并返回
        return Result.ok(blogScrollResult);
    }


    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
