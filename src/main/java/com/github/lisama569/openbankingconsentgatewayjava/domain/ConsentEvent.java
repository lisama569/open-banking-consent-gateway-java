package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;

/** 授权事件，按授权维度（consentId）有序。 */
public class ConsentEvent {
    private final String eventId;
    private final String consentId;
    private final String clientId;
    private final ConsentEventType type;
    /** 同一 consentId 下从 1 开始单调递增。 */
    private final long sequence;
    private final Instant occurredAt;

    public ConsentEvent(String eventId, String consentId, String clientId,
                        ConsentEventType type, long sequence, Instant occurredAt) {
        this.eventId = eventId;
        this.consentId = consentId;
        this.clientId = clientId;
        this.type = type;
        this.sequence = sequence;
        this.occurredAt = occurredAt;
    }

    public String getEventId() { return eventId; }
    public String getConsentId() { return consentId; }
    public String getClientId() { return clientId; }
    public ConsentEventType getType() { return type; }
    public long getSequence() { return sequence; }
    public Instant getOccurredAt() { return occurredAt; }
}
