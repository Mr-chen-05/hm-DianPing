package com.hmdp.utils;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RedisData<T> {
    private LocalDateTime expireTime;
    private T data;

}
