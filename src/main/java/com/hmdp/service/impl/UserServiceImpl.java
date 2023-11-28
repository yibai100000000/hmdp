package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.Map;
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

    @Autowired
    public RedisTemplate redisTemplate;

    @Override
    public Result getCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);

//        session.setAttribute("code",code);
        redisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //可集成验证码
        log.info("发送验证码成功,{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }

        String code=loginForm.getCode();
        String phone=loginForm.getPhone();

        Object redisCode = redisTemplate.opsForValue().get(LOGIN_CODE_KEY+loginForm.getPhone());
        if(redisCode==null || !code.equals(redisCode.toString())){
            return Result.fail("验证码错误");
        }

        //根据手机号查询用户
        User user=query().eq("phone",phone).one();
        if(user==null){
            //保存新用户到数据库
            user=createUserWithPhone(phone);
        }

        //session.setAttribute("user",BeanUtil.copyProperties(user,UserDTO.class));

        //随机生成Token,用做redis的key和前端的令牌
        String token = UUID.randomUUID().toString(true);
        //将user转换为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO);
        //保存
        String tokenKey=LOGIN_USER_KEY+token;
        redisTemplate.opsForHash().putAll(tokenKey,map);
        //设置token有效期
        redisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.SECONDS);
        //返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(6));
        save(user);
        return user;
    }
}
