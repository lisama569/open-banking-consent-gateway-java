package com.github.lisama569.openbankingconsentgatewayjava.domain;

/** 通知投递状态。 */
public enum DeliveryStatus {
    PENDING,
    DELIVERED,
    /** 重试耗尽后仍未送达 */
    FAILED
}
