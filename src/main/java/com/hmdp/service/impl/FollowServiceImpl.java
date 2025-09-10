package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.exception.FollowBusinessException;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.UserVO;
import com.hmdp.vo.mapper.UserVOMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private UserVOMapper userVOMapper;

    private static final DefaultRedisScript<List> FOLLOW_SCRIPT;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // shift + f6 同时修改变量
    static {
        FOLLOW_SCRIPT = new DefaultRedisScript<>();
        FOLLOW_SCRIPT.setLocation(new ClassPathResource("isFollow.lua"));
        FOLLOW_SCRIPT.setResultType(List.class);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取当前用户id
        Long userId = UserHolder.getUser().getId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }
        // 明确转换为String类型
        String followKey = RedisConstants.FOLLOW_KEY + userId;
        String unfollowKey = RedisConstants.UN_FOLLOW_KEY + userId;
        String userBlogKey = RedisConstants.BLOG_COUNTER_KEY + followUserId;
        String blogFansKey = RedisConstants.BLOG_FANS_KEY + followUserId;

        try {
            // 使用明确的String列表
            List<String> keys = Arrays.asList(followKey, unfollowKey, userBlogKey,blogFansKey);

            // 确保所有参数都是明确的String类型
            String targetUserIdStr = String.valueOf(followUserId);
            String operationTypeStr = isFollow ? "1" : "0";
            String currentUserIdStr = String.valueOf(userId);

            // 执行Redis操作
            List<Object> result = stringRedisTemplate.execute(FOLLOW_SCRIPT, keys, targetUserIdStr, operationTypeStr, currentUserIdStr);
            // 等待Redis操作完成并获取结果
            if (CollectionUtils.isEmpty(result)) {
                log.error("执行Redis操作异常:{}", result);
                return Result.fail("操作失败");
            }
            // 获取消息结果
            String message = result.get(1).toString();
            // ✅ 在主线程中提前获取代理对象
            IFollowService iFollowServiceProxy = (IFollowService) AopContext.currentProxy();
            // 异步执行数据库操作
            CompletableFuture.runAsync(() -> {
                try {
                    iFollowServiceProxy.addOrRemoveResult(followUserId, result, userId);
                } catch (Exception e) {
                    log.error("数据库异步操作执行失败: userId={}, followUserId={}", userId, followUserId, e);
                }
            }, CACHE_REBUILD_EXECUTOR);
            return Result.ok(message);
        } catch (Exception e) {
            log.error("执行关注/取消关注操作失败", e);
            return Result.fail("操作失败");
        }

        // 执行脚本 - 使用可变参数而不是List
        // List<Object> result = stringRedisTemplate.execute(FOLLOW_SCRIPT, keys, targetUserIdStr, operationTypeStr, currentUserIdStr);
        // IFollowService iFollowServiceProxy = (IFollowService) AopContext.currentProxy();
        // return iFollowServiceProxy.addOrRemoveResult(followUserId, result, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void addOrRemoveResult(Long followUserId, List<Object> result, Long userId) {
        Long status = (Long) result.get(0);
        String message = result.get(1).toString();
        if (status == 2) {
            // 用户未关注，可以关注
            Follow follow = new Follow().setUserId(userId).setFollowUserId(followUserId);
            // 保存数据
            boolean save = save(follow);
            if (!save) {
                throw new FollowBusinessException("执行关注操作异常!");
            } else {
                log.info(message);
            }
        } else if (status == -2) {
            // 删除数据
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if (!remove) {
                throw new FollowBusinessException("执行取消关注操作异常!");
            } else {
                log.info(message);
            }
        } else {
            log.info(message);
        }
    }

    @Override
    public Result common(Long id) {
        Long userId = UserHolder.getUser().getId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(RedisConstants.FOLLOW_KEY + userId, RedisConstants.FOLLOW_KEY + id);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::parseLong).toList();
        List<UserVO> userVOList = userService.listByIds(ids).stream().map(user -> userVOMapper.entityToUserVO(user)).toList();
        return Result.ok(userVOList);
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 获取当前用户id
        Long userId = UserHolder.getUser().getId();
        // 查询关注状态
        Boolean member = stringRedisTemplate.opsForSet().isMember(RedisConstants.FOLLOW_KEY + userId, followUserId.toString());
        return Result.ok(BooleanUtils.isTrue(member));
    }
}
