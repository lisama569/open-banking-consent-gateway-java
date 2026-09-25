package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;

/** 合作伙伴客户端凭据。 */
public class Credential {
    private final String credentialId;
    private final String secret;
    private volatile CredentialStatus status;
    private final Instant createdAt;
    /** 仅 GRACE 状态有意义：过渡期截止时间。 */
    private volatile Instant graceUntil;

    public Credential(String credentialId, String secret, CredentialStatus status, Instant createdAt, Instant graceUntil) {
        this.credentialId = credentialId;
        this.secret = secret;
        this.status = status;
        this.createdAt = createdAt;
        this.graceUntil = graceUntil;
    }

    public String getCredentialId() { return credentialId; }
    public String getSecret() { return secret; }
    public CredentialStatus getStatus() { return status; }
    public void setStatus(CredentialStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getGraceUntil() { return graceUntil; }
    public void setGraceUntil(Instant graceUntil) { this.graceUntil = graceUntil; }
}
