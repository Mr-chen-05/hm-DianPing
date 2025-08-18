package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.mapper.UserDTOMapper;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserDTOMapper userDTOMapper;
    // 用户
    private static final String USER = "user";

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返货错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis中  redis的key的命名规范：项目名：业务名：业务功能：key; .set(key, value, timeout过期时间的数量, unit时间单位：分钟)
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 6.返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返货错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (ObjectUtils.isEmpty(cacheCode) || !cacheCode.equals(code)) {
            // 3.不一致，报错
            return Result.fail("验证码错误！");
        }
        // 4.一致，根据手机号查询用户
        User user = lambdaQuery()
                .eq(User::getPhone, phone)
                .one();
        // 5.判断用户是否存在
        if (ObjectUtils.isEmpty(user)) {
            // 6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 7.保存用户信息到redis中
        // 7.1 随机生成token，作为登录令牌 ;true代表没有下划线
        String token = UUID.randomUUID().toString(true);
        // 7.2 将User对象转为HashMap存储
        UserDTO userDTO = userDTOMapper.toUserDTO(user);
        // beanTOMap 其中第二个参数 setIgnoreNullValue(true) 表示忽略 null 值，.setFieldValueEditor表示把字段值转为字符串的相关操作
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.3 设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8. 返回token
        if (ObjectUtils.isNotEmpty(user)) {
            return Result.ok(token);
        } else {
            return Result.fail("登录失败！");
        }
    }

    /**
     * 创建新用户并保存
     *
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        //   1.创建用户
        User user = new User()
                .setPhone(phone)
                .setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //
        return save(user) ? user : null;
    }
}
