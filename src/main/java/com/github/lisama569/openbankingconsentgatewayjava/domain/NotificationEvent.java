package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;

/** 授权事件通知，按授权维度（consentId）单调递增序号排序。 */
public class NotificationEvent {
    private final String eventId;
    private final String consentId;
    private final String clientId;
    private final ConsentEventType type;
    private final long sequence;
    private final long consentVersion;
    private final Instant createdAt;
    private volatile DeliveryStatus status = DeliveryStatus.PENDING;
    private volatile int attempts;
    private volatile String lastError;

    public NotificationEvent(String eventId, String consentId, String clientId, ConsentEventType type,
                             long sequence, long consentVersion, Instant createdAt) {
        this.eventId = eventId;
        this.consentId = consentId;
        this.clientId = clientId;
        this.type = type;
        this.sequence = sequence;
        this.consentVersion = consentVersion;
        this.createdAt = createdAt;
    }

    public String getEventId() { return eventId; }
    public String getConsentId() { return consentId; }
    public String getClientId() { return clientId; }
    public ConsentEventType getType() { return type; }
    public long getSequence() { return sequence; }
    public long getConsentVersion() { return consentVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
