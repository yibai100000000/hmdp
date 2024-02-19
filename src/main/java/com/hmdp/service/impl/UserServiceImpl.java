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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

    @Override
    public Result sign() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix= now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月第几天
        int dayOfMonth=now.getDayOfMonth();
        //写入redis setBit key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);

        return Result.ok();
    }


    @Override
    public Result signCount() {

        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix= now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月第几天
        int dayOfMonth=now.getDayOfMonth();


        //获取本月截至今天所有签到记录，返回十进制 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        //没有签到
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num=result.get(0);
        if(num==null || num==0){
            return Result.ok(0);
        }

        int count=0;
        //循环遍历
        while(true){
            if((num & 1) == 0){
                //为0，结束
                break;
            }else{
                //不为0，计数器+1
                count++;
            }

            //右移赋值
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
