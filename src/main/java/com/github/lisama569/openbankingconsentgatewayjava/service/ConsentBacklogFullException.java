package com.github.lisama569.openbankingconsentgatewayjava.service;

/**
 * 某条授权下未送达事件积压达到容量上限时抛出，
 * 与投递失败（FAILED）、限流（RATE_LIMITED）明确区分的独立原因。
 */
public class ConsentBacklogFullException extends RuntimeException {
    private final String consentId;

    public ConsentBacklogFullException(String consentId, int capacity) {
        super("consent " + consentId + " notification backlog full (capacity=" + capacity + ")");
        this.consentId = consentId;
    }

    public String getConsentId() { return consentId; }
}
