package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;

/** 通知投递记录。 */
public class DeliveryRecord {
    private final String eventId;
    private volatile DeliveryStatus status;
    private volatile int attempts;
    private volatile Instant lastAttemptAt;
    private volatile String lastError;

    public DeliveryRecord(String eventId) {
        this.eventId = eventId;
        this.status = DeliveryStatus.PENDING;
        this.attempts = 0;
    }

    public String getEventId() { return eventId; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
