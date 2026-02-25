package com.huxirating.utils;

public interface ILock {
    boolean tryLock(Long timeoutSec);
    void unLock();
}
