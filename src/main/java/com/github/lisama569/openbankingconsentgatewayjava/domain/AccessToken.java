package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;
import java.util.Set;

/** 访问令牌。 */
public class AccessToken {
    private final String tokenId;
    private final String consentId;
    private final String clientId;
    private final Set<String> scopes;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private volatile boolean revoked;

    public AccessToken(String tokenId, String consentId, String clientId, Set<String> scopes,
                       Instant issuedAt, Instant expiresAt) {
        this.tokenId = tokenId;
        this.consentId = consentId;
        this.clientId = clientId;
        this.scopes = scopes;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    public String getTokenId() { return tokenId; }
    public String getConsentId() { return consentId; }
    public String getClientId() { return clientId; }
    public Set<String> getScopes() { return scopes; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
