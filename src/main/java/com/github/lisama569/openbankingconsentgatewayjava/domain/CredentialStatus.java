package com.github.lisama569.openbankingconsentgatewayjava.domain;

/** 凭据状态：ACTIVE=当前有效；GRACE=轮换过渡期仍可用；RETIRED=已退役不可用。 */
public enum CredentialStatus {
    ACTIVE,
    GRACE,
    RETIRED
}
