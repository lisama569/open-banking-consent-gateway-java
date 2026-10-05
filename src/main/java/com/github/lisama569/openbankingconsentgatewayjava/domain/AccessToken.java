package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;
import java.util.Set;

/** 访问令牌（不透明字符串，本地存储）。 */
public class AccessToken {
    private final String tokenId;
    private final String clientId;
    private final Set<String> scopes;
    private final Instant issuedAt;
    private final Instant expiresAt;
    /** 绑定的用户授权 ID；null 表示不绑定授权的旧凭证（兼容入口签发）。 */
    private final String consentId;
    /** 换凭证时授权被用户确认的版本；授权后续版本变更会使本凭证立即失效。 */
    private final Long consentVersion;
    private volatile boolean revoked;

    public AccessToken(String tokenId, String clientId, Set<String> scopes, Instant issuedAt, Instant expiresAt) {
        this(tokenId, clientId, scopes, issuedAt, expiresAt, null, null);
    }

    public AccessToken(String tokenId, String clientId, Set<String> scopes, Instant issuedAt, Instant expiresAt,
                       String consentId, Long consentVersion) {
        this.tokenId = tokenId;
        this.clientId = clientId;
        this.scopes = Set.copyOf(scopes);
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.consentId = consentId;
        this.consentVersion = consentVersion;
    }

    public String getTokenId() { return tokenId; }
    public String getClientId() { return clientId; }
    public Set<String> getScopes() { return scopes; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getConsentId() { return consentId; }
    public Long getConsentVersion() { return consentVersion; }
    /** 是否绑定具体用户授权（旧入口签发的令牌不绑定）。 */
    public boolean isConsentBound() { return consentId != null; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
