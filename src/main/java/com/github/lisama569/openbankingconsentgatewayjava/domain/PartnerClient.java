package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 合作伙伴客户端。 */
public class PartnerClient {
    private final String clientId;
    private final String name;
    private final Instant registeredAt;
    private volatile boolean suspended;
    private final List<ClientCredential> credentials = new CopyOnWriteArrayList<>();

    public PartnerClient(String clientId, String name, Instant registeredAt) {
        this.clientId = clientId;
        this.name = name;
        this.registeredAt = registeredAt;
    }

    public String getClientId() { return clientId; }
    public String getName() { return name; }
    public Instant getRegisteredAt() { return registeredAt; }
    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean suspended) { this.suspended = suspended; }
    public List<ClientCredential> getCredentials() { return credentials; }
}
