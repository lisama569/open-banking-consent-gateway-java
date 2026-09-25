package com.github.lisama569.openbankingconsentgatewayjava.domain;

/** 用户授权生命周期状态。 */
public enum ConsentStatus {
    /** 已创建，等待用户确认 */
    PENDING_CONFIRMATION,
    /** 用户已确认，授权生效 */
    AUTHORIZED,
    /** 用户拒绝 */
    REJECTED,
    /** 已撤销（终态） */
    REVOKED,
    /** 已过期（终态） */
    EXPIRED
}
