package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;

/** 令牌校验结果：成功时携带令牌，失败时携带可区分原因。 */
public record TokenValidationResult(boolean valid, AccessToken token, TokenRejectionReason reason) {
    public static TokenValidationResult ok(AccessToken token) {
        return new TokenValidationResult(true, token, null);
    }
    public static TokenValidationResult reject(TokenRejectionReason reason) {
        return new TokenValidationResult(false, null, reason);
    }
}
