package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.interceptor.RefreshTokenInterceptor.TOKEN_HOLDER;
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
        log.info("原userDTO:{}",userDTO);
        log.info("icon == null? {}", userDTO.getIcon() == null);
        log.info("icon.isEmpty()? {}", userDTO.getIcon() != null && userDTO.getIcon().isEmpty());
        // beanTOMap 其中第二个参数 setIgnoreNullValue(true) 表示忽略 null 值，.setFieldValueEditor表示把字段值转为字符串的相关操作
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())
        );
        log.info("新userMap:{}",userMap);
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.3 设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8. 返回token
        if (ObjectUtils.isNotEmpty(user)) {
            log.info("登录成功！token：{}", token);
            return Result.ok(token);
        } else {
            return Result.fail("登录失败！");
        }
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入redis setBit key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        log.info(key);
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有签到记录，返回的是一个十进制的数字 BITFIELD hm-DianPing:sign:353:2025:09 get u10 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()){
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == 0 || num == null){
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true){
            // 7.1让这个数字与1做与运算，得到数字的最后一个bit位
            // 7.2判断这个bit位是否为0
            if ((num & 1)== 0) {
                // 如果为0, 说明未签到,结束
                break;
            }else {
                // 如果不为0,说明已签到,计数器+1
                count++;
            }
            // 把数字右移一位,抛弃最后一个bit位,继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    @Override
    public Result logout() {
        // 删除当前用户token
        Boolean delete = stringRedisTemplate.delete(LOGIN_USER_KEY + TOKEN_HOLDER);
        if (BooleanUtil.isFalse(delete)){
            return Result.fail("退出登录失败！");
        }
        return Result.ok("退出登录成功！");
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
