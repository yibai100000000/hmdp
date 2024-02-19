package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog=getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        //查询blog相关用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

        //查询blog是否被点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    public void isBlogLiked(Blog blog) {
        //获取登录用户
        UserDTO user= UserHolder.getUser();
        if(user == null){
            return;
        }
        //判断是否已经点赞
        String key="blog:liked:"+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score!=null);

    }


    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId= UserHolder.getUser().getId();
        //判断是否已经点赞
        String key="blog:liked:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){
            //如果未点赞，可以点赞
            //  数据库点赞数+1
            boolean isSuccess=update().setSql("liked=liked+1").eq("id",id).update();
            //  保存用户到Redis的set集合
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            //如果已点赞，取消点赞
            //  数据库点赞数-1
            boolean isSuccess=update().setSql("liked=liked-1").eq("id",id).update();
            //  把用户从set中移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }




        return null;
    }


    @Override
    public List<Blog> queryHot(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());

            isBlogLiked(blog);
        });

        return records;
    }


    @Override
    public Result queryBlogLikes(Long id) {

        //查询top5的点赞用户
        String key="blog:liked:"+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //解析用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        //先后排序的处理
        String idStr= StrUtil.join(",",ids);

        //根据用户id查询用户 WHERE id IN(5,1) ORDER BY FIELD(id,5,1)
        //使用自定义查询语句
//        List<UserDTO> users = userService.listByIds(ids)
//                .stream()
//                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
//                .collect(Collectors.toList());
        List<UserDTO> users = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //返回

        return Result.ok(users);
    }
}
