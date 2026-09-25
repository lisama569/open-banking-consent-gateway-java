package com.github.lisama569.openbankingconsentgatewayjava.store;

import com.github.lisama569.openbankingconsentgatewayjava.domain.PartnerClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 合作伙伴客户端本地存储。 */
@Component
public class ClientStore {
    private final ConcurrentHashMap<String, PartnerClient> clients = new ConcurrentHashMap<>();

    public PartnerClient save(PartnerClient client) {
        clients.put(client.getClientId(), client);
        return client;
    }

    public Optional<PartnerClient> findById(String clientId) {
        return Optional.ofNullable(clients.get(clientId));
    }
}
