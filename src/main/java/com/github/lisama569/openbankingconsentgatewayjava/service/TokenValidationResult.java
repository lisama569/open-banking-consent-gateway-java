package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.domain.TokenRejectionReason;

/** 令牌校验结果：成功时携带令牌，失败时携带可区分的拒绝原因。 */
public record TokenValidationResult(boolean accepted, AccessToken token, TokenRejectionReason rejectionReason) {

    public static TokenValidationResult accepted(AccessToken token) {
        return new TokenValidationResult(true, token, null);
    }

    public static TokenValidationResult rejected(TokenRejectionReason reason) {
        return new TokenValidationResult(false, null, reason);
    }
}
