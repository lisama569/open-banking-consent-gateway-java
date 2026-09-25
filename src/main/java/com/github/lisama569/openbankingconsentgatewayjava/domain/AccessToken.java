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
    private volatile boolean revoked;

    public AccessToken(String tokenId, String clientId, Set<String> scopes, Instant issuedAt, Instant expiresAt) {
        this.tokenId = tokenId;
        this.clientId = clientId;
        this.scopes = Set.copyOf(scopes);
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public String getTokenId() { return tokenId; }
    public String getClientId() { return clientId; }
    public Set<String> getScopes() { return scopes; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
