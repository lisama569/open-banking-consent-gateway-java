package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;

/** 合作伙伴客户端凭据（本地模拟，仅存哈希，不存明文）。 */
public class ClientCredential {
    private final String credentialId;
    private final String secretHash;
    private final Instant createdAt;
    private volatile CredentialStatus status;
    /** 仅 GRACE 状态有意义：宽限期截止时间 */
    private volatile Instant graceUntil;

    public ClientCredential(String credentialId, String secretHash, Instant createdAt) {
        this.credentialId = credentialId;
        this.secretHash = secretHash;
        this.createdAt = createdAt;
        this.status = CredentialStatus.ACTIVE;
    }

    public String getCredentialId() { return credentialId; }
    public String getSecretHash() { return secretHash; }
    public Instant getCreatedAt() { return createdAt; }
    public CredentialStatus getStatus() { return status; }
    public void setStatus(CredentialStatus status) { this.status = status; }
    public Instant getGraceUntil() { return graceUntil; }
    public void setGraceUntil(Instant graceUntil) { this.graceUntil = graceUntil; }
}
