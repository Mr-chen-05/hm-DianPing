package com.hmdp.handler;

import com.hmdp.dto.Result;
import com.hmdp.exception.BaseException;
import com.hmdp.exception.CacheRebuildTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(CacheRebuildTimeoutException.class)
    public Result handleCacheRebuildTimeout(CacheRebuildTimeoutException ex) {
        log.error("缓存重建超时:{}", ex.getMessage());
        return Result.fail(ex.getMessage());
    }

    @ExceptionHandler
    public Result handleOtherException(BaseException ex) {
        log.error("系统异常:{}", ex.getMessage());
        return Result.fail(ex.getMessage());
    }
}