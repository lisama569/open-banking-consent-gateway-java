package com.github.lisama569.openbankingconsentgatewayjava.domain;

/** 客户端凭据状态。 */
public enum CredentialStatus {
    /** 当前生效凭据 */
    ACTIVE,
    /** 已被轮换，但仍处于过渡宽限期，可继续用于认证 */
    GRACE,
    /** 已彻底失效 */
    RETIRED
}
