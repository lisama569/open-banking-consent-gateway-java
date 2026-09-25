package com.github.lisama569.openbankingconsentgatewayjava.domain;

/** 令牌校验拒绝原因，须可区分。 */
public enum TokenRejectionReason {
    UNKNOWN_TOKEN,
    TOKEN_EXPIRED,
    TOKEN_REVOKED,
    CONSENT_REVOKED,
    CONSENT_EXPIRED,
    INSUFFICIENT_SCOPE
}
