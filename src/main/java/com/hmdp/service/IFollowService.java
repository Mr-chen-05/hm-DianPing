package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {
    /**
     * 关注或取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 查询关注状态
     * @param followUserId
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 添加关注结果
     * @param followUserId
     * @param result
     * @param userId
     * @return
     */
    void addOrRemoveResult(Long followUserId, List<Object> result, Long userId);

    /**
     * 获取共同关注
     * @param id
     * @return
     */
    Result common(Long id);
}
