package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;
import java.util.Set;

/** 用户授权记录。 */
public class Consent {
    private final String consentId;
    private final String clientId;
    private final String userId;
    private final Set<String> scopes;
    private final Instant createdAt;
    private volatile Instant expiresAt;
    private volatile ConsentStatus status;
    /** 每次范围变更递增，用于事件排序与重新确认 */
    private volatile long version;

    public Consent(String consentId, String clientId, String userId, Set<String> scopes,
                   Instant createdAt, Instant expiresAt) {
        this.consentId = consentId;
        this.clientId = clientId;
        this.userId = userId;
        this.scopes = Set.copyOf(scopes);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = ConsentStatus.PENDING_CONFIRMATION;
        this.version = 0;
    }

    public String getConsentId() { return consentId; }
    public String getClientId() { return clientId; }
    public String getUserId() { return userId; }
    public Set<String> getScopes() { return scopes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public ConsentStatus getStatus() { return status; }
    public void setStatus(ConsentStatus status) { this.status = status; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
