package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lisama569.openbankingconsentgatewayjava.domain.Credential;
import com.github.lisama569.openbankingconsentgatewayjava.domain.CredentialStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.PartnerClient;
import com.github.lisama569.openbankingconsentgatewayjava.store.ClientStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 合作伙伴接入与凭据轮换。
 * 轮换语义：新凭据立即 ACTIVE；旧凭据进入 GRACE，过渡期内仍可用；
 * 过渡期结束后旧凭据自动视为 RETIRED，不再可用。
 */
@Service
public class ClientService {
    private static final Logger log = LoggerFactory.getLogger(ClientService.class);

    private final ClientStore clientStore;
    private final GatewayProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ClientService(ClientStore clientStore, GatewayProperties properties) {
        this(clientStore, properties, Clock.systemUTC());
    }

    public ClientService(ClientStore clientStore, GatewayProperties properties, Clock clock) {
        this.clientStore = clientStore;
        this.properties = properties;
        this.clock = clock;
    }

    /** 接入：注册客户端并签发首个 ACTIVE 凭据。重复接入同一 clientId 幂等返回已有客户端。 */
    public PartnerClient onboard(String clientId, String name) {
        Optional<PartnerClient> existing = clientStore.findById(clientId);
        if (existing.isPresent()) {
            log.info("onboard clientId={} result=already-exists", clientId);
            return existing.get();
        }
        PartnerClient client = new PartnerClient(clientId, name);
        client.getCredentials().add(newCredential(CredentialStatus.ACTIVE, null));
        clientStore.save(client);
        log.info("onboard clientId={} result=created credentials={}", clientId, client.getCredentials().size());
        return client;
    }

    /** 轮换：签发新 ACTIVE 凭据，旧 ACTIVE 凭据降级为 GRACE（过渡期内仍可用）。 */
    public synchronized Credential rotateCredential(String clientId) {
        PartnerClient client = clientStore.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("unknown client: " + clientId));
        Instant now = clock.instant();
        Instant graceUntil = now.plusSeconds(properties.getCredentialGraceSeconds());
        for (Credential c : client.getCredentials()) {
            if (c.getStatus() == CredentialStatus.ACTIVE) {
                c.setStatus(CredentialStatus.GRACE);
                c.setGraceUntil(graceUntil);
            } else if (c.getStatus() == CredentialStatus.GRACE) {
                c.setStatus(CredentialStatus.RETIRED);
            }
        }
        Credential fresh = newCredential(CredentialStatus.ACTIVE, null);
        client.getCredentials().add(fresh);
        log.info("rotate clientId={} newCredentialId={} graceUntil={}", clientId, fresh.getCredentialId(), graceUntil);
        return fresh;
    }

    /**
     * 认证：clientId + credentialId + secret 匹配且凭据当前可用。
     * ACTIVE 恒可用；GRACE 仅在过渡期截止前可用；RETIRED 不可用。
     */
    public Optional<PartnerClient> authenticate(String clientId, String credentialId, String secret) {
        Optional<PartnerClient> clientOpt = clientStore.findById(clientId);
        if (clientOpt.isEmpty()) {
            log.info("authenticate clientId={} result=reject reason=unknown-client", clientId);
            return Optional.empty();
        }
        PartnerClient client = clientOpt.get();
        Instant now = clock.instant();
        for (Credential c : client.getCredentials()) {
            if (!c.getCredentialId().equals(credentialId)) {
                continue;
            }
            if (!MessageDigest.isEqual(c.getSecret().getBytes(), secret.getBytes())) {
                log.info("authenticate clientId={} credentialId={} result=reject reason=bad-secret", clientId, credentialId);
                return Optional.empty();
            }
            boolean usable = switch (c.getStatus()) {
                case ACTIVE -> true;
                case GRACE -> c.getGraceUntil() != null && now.isBefore(c.getGraceUntil());
                case RETIRED -> false;
            };
            log.info("authenticate clientId={} credentialId={} status={} result={}",
                    clientId, credentialId, c.getStatus(), usable ? "accept" : "reject reason=credential-" + c.getStatus().name().toLowerCase());
            return usable ? Optional.of(client) : Optional.empty();
        }
        log.info("authenticate clientId={} credentialId={} result=reject reason=unknown-credential", clientId, credentialId);
        return Optional.empty();
    }

    private Credential newCredential(CredentialStatus status, Instant graceUntil) {
        byte[] idBytes = new byte[8];
        byte[] secretBytes = new byte[24];
        random.nextBytes(idBytes);
        random.nextBytes(secretBytes);
        return new Credential("cred-" + HexFormat.of().formatHex(idBytes),
                HexFormat.of().formatHex(secretBytes), status, clock.instant(), graceUntil);
    }
}
