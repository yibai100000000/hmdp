package com.hmdp.utils;

public interface ILcok {

    boolean tryLock(long timeoutSec);

    void unLock();
}
