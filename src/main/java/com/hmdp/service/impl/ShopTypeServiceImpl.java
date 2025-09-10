package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String shopTypeKey = RedisConstants.CACHE_SHOP_TYPE_KEY;
        // 1.从redis查询所有商铺种类缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(shopTypeKey);
        // 2.判断是否存在，存在，直接返回
        if (StringUtils.isNoneBlank(shopTypeJson)) {
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 3.不存在，查询数据库并判断是否有数据
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (CollectionUtils.isEmpty(shopTypeList)) {
            // 4.数据库查询也不存在，返回错误
            Result.fail("商铺类型为空！");
        }
        // 5.存在，存入redis中
        stringRedisTemplate.opsForValue().set(shopTypeKey, JSONUtil.toJsonStr(shopTypeList), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6.返回
        return Result.ok(shopTypeList);
    }
}
