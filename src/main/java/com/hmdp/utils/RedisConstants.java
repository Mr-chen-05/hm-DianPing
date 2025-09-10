package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "hm-DianPing:user:login:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "hm-DianPing:user:token:";
    public static final Long LOGIN_USER_TTL = 360000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "hm-DianPing:shop:query:";

    public static final String CACHE_SHOP_TYPE_KEY = "hm-DianPing:shop:typeQuery:1";

    public static final String LOCK_SHOP_KEY = "hm-DianPing:lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String LOCK_ORDER_KEY = "hm-DianPing:lock:order:";

    public static final String SECKILL_TYPE_KEY = "hm-DianPing:seckill:type:";
    public static final String BLOG_LIKED_KEY = "hm-DianPing:blog:liked:";
    // 博客点赞数key，Hash结构的计数器
    public static final String BLOG_LIKED_COUNT_KEY = "hm-DianPing:blog:counter:count";
    // 用户发的博客记录
    public static final String BLOG_COUNTER_KEY = "hm-DianPing:blog:addCount:";
    // 博客记录key
    // public static final String BLOG_ADD_KEY = "hm-DianPing:blog:addBlog:";
    // 博客粉丝记录
    public static final String BLOG_FANS_KEY = "hm-DianPing:follow:fans:";
    // 关注
    public static final String FOLLOW_KEY = "hm-DianPing:follow:userFollow:";

    // 取关
    public static final String UN_FOLLOW_KEY = "hm-DianPing:follow:unUserFollow:";
    public static final String FEED_KEY = "hm-DianPing:blog:feed:";
    // 博客推送给粉丝的key
    public static final String BLOG_PUSH_FANS_KEY = "hm-DianPing:blog:pushFans:";
    public static final String SHOP_GEO_KEY = "hm-DianPing:shop:geo:";
    public static final String USER_SIGN_KEY = "hm-DianPing:sign:";

}
