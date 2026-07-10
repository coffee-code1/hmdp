package com.hmdp.utils;

public interface ILOCK {

    boolean tryLock(Long timeoutSec);

    void unlock();
}
