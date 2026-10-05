package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 绑定用户授权的访问凭证。
 *
 * 与不含授权绑定的普通 {@link AccessToken} 的区别：本凭证绑定 (consentId, consentVersion)，
 * 其有效范围不超过授权确认时的范围；授权撤销/过期/范围变更后凭证立即失效，
 * 失效原因可在校验时明确区分。
 */
public class ConsentAccessToken extends AccessToken {
    private final String consentId;
    /** 签发时授权的版本号，用于检测范围变更 */
    private final long consentVersion;

    public ConsentAccessToken(String tokenId, String consentId, String clientId, Set<String> scopes,
                              long consentVersion, Instant issuedAt, Instant expiresAt) {
        super(tokenId, clientId, scopes, issuedAt, expiresAt);
        this.consentId = consentId;
        this.consentVersion = consentVersion;
    }

    public String getConsentId() { return consentId; }
    public long getConsentVersion() { return consentVersion; }
}
