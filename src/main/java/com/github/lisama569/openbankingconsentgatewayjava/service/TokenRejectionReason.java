package com.github.lisama569.openbankingconsentgatewayjava.service;

/** 令牌/凭据校验的可区分拒绝原因。 */
public enum TokenRejectionReason {
    UNKNOWN_CLIENT,
    CLIENT_SUSPENDED,
    BAD_CREDENTIAL,
    CREDENTIAL_RETIRED,
    TOKEN_NOT_FOUND,
    TOKEN_EXPIRED,
    TOKEN_REVOKED,
    SCOPE_NOT_GRANTED
}
