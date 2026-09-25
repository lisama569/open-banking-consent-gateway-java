package com.github.lisama569.openbankingconsentgatewayjava.domain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 合作伙伴客户端。 */
public class PartnerClient {
    private final String clientId;
    private final String name;
    private final List<Credential> credentials = new CopyOnWriteArrayList<>();

    public PartnerClient(String clientId, String name) {
        this.clientId = clientId;
        this.name = name;
    }

    public String getClientId() { return clientId; }
    public String getName() { return name; }
    public List<Credential> getCredentials() { return credentials; }
}
