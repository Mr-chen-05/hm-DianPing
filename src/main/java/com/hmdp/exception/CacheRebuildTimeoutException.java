package com.hmdp.exception;

/**
 * 缓存重建异常
 */
public class CacheRebuildTimeoutException extends BaseException {

    public CacheRebuildTimeoutException() {
    }
    public CacheRebuildTimeoutException(String message) {
        super(message);
    }
}
