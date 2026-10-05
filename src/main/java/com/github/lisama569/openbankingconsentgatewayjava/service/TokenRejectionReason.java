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
    SCOPE_NOT_GRANTED,
    /** 授权尚未被用户确认，不能换取凭证 */
    CONSENT_NOT_AUTHORIZED,
    /** 绑定凭证所依据的授权已被撤销 */
    CONSENT_REVOKED,
    /** 绑定凭证所依据的授权已过期 */
    CONSENT_EXPIRED,
    /** 授权范围已变更、需用户重新确认，旧版本凭证失效 */
    CONSENT_SCOPE_CHANGED
}
