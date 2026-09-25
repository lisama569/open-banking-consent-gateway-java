package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;
import java.util.Set;

/** 用户授权。 */
public class Consent {
    private final String consentId;
    private final String clientId;
    private final String userId;
    private volatile Set<String> scopes;
    private volatile ConsentStatus status;
    private final Instant createdAt;
    private volatile Instant expiresAt;
    /** 单调递增版本，范围变更时递增。 */
    private volatile long version;

    public Consent(String consentId, String clientId, String userId, Set<String> scopes,
                   ConsentStatus status, Instant createdAt, Instant expiresAt, long version) {
        this.consentId = consentId;
        this.clientId = clientId;
        this.userId = userId;
        this.scopes = scopes;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.version = version;
    }

    public String getConsentId() { return consentId; }
    public String getClientId() { return clientId; }
    public String getUserId() { return userId; }
    public Set<String> getScopes() { return scopes; }
    public void setScopes(Set<String> scopes) { this.scopes = scopes; }
    public ConsentStatus getStatus() { return status; }
    public void setStatus(ConsentStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
