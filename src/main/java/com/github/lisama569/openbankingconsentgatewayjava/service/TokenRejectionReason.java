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
    // ---- 授权绑定凭证专属原因 ----
    /** 换凭证时授权不存在 */
    CONSENT_NOT_FOUND,
    /** 换凭证时授权尚未被用户确认（PENDING_CONFIRMATION） */
    CONSENT_NOT_AUTHORIZED,
    /** 绑定的授权已被用户撤销，基于它换出的凭证立即失效 */
    CONSENT_REVOKED,
    /** 绑定的授权已自然到期，基于它换出的凭证立即失效 */
    CONSENT_EXPIRED,
    /** 授权范围已变更（版本变化），旧凭证不再可用，需要用户重新确认后重新换凭证 */
    CONSENT_SCOPE_RECONFIRMATION_REQUIRED,
    /** 换凭证时请求范围超出该授权当前已确认范围 */
    CONSENT_SCOPE_EXCEEDED
}
