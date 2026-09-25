package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.ClientCredential;
import com.github.lisama569.openbankingconsentgatewayjava.domain.CredentialStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.PartnerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合作伙伴接入与凭据轮换。
 *
 * 轮换语义：rotate 后新凭据立即生效（ACTIVE），旧凭据进入 GRACE，
 * 宽限期内新旧凭据均可认证；宽限期到期后旧凭据变为 RETIRED，认证返回 CREDENTIAL_RETIRED。
 */
@Service
public class PartnerService {
    private static final Logger log = LoggerFactory.getLogger(PartnerService.class);

    /** 注册新客户端，返回初始凭据明文（仅此一次可见）。 */
    public record IssuedCredential(String credentialId, String plainSecret, ClientCredential credential) {}

    private final Map<String, PartnerClient> clients = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public PartnerService() {
        this(Clock.systemUTC());
    }

    public PartnerService(Clock clock) {
        this.clock = clock;
    }

    public PartnerClient register(String clientId, String name) {
        PartnerClient client = new PartnerClient(clientId, name, Instant.now(clock));
        PartnerClient existing = clients.putIfAbsent(clientId, client);
        if (existing != null) {
            log.info("[partner] register duplicate clientId={} -> 返回已有记录", clientId);
            return existing;
        }
        log.info("[partner] register clientId={} name={}", clientId, name);
        return client;
    }

    public IssuedCredential issueInitialCredential(String clientId) {
        PartnerClient client = clients.get(clientId);
        if (client == null) {
            throw new IllegalArgumentException("unknown client: " + clientId);
        }
        synchronized (client) {
            // 幂等：已有 ACTIVE 凭据时不重复签发（明文不可恢复，返回 null 明文）
            for (ClientCredential c : client.getCredentials()) {
                if (c.getStatus() == CredentialStatus.ACTIVE) {
                    log.info("[partner] issueInitialCredential clientId={} 已存在 ACTIVE 凭据 credentialId={}", clientId, c.getCredentialId());
                    return new IssuedCredential(c.getCredentialId(), null, c);
                }
            }
            return issueLocked(client);
        }
    }

    /** 轮换凭据：新凭据生效，旧凭据进入宽限期。 */
    public IssuedCredential rotateCredential(String clientId, Duration gracePeriod) {
        PartnerClient client = clients.get(clientId);
        if (client == null) {
            throw new IllegalArgumentException("unknown client: " + clientId);
        }
        synchronized (client) {
            Instant now = Instant.now(clock);
            for (ClientCredential c : client.getCredentials()) {
                if (c.getStatus() == CredentialStatus.ACTIVE) {
                    c.setStatus(CredentialStatus.GRACE);
                    c.setGraceUntil(now.plus(gracePeriod));
                    log.info("[partner] rotate clientId={} 旧凭据 {} 进入宽限期, graceUntil={}",
                            clientId, c.getCredentialId(), c.getGraceUntil());
                }
            }
            IssuedCredential issued = issueLocked(client);
            log.info("[partner] rotate clientId={} 新凭据 {}", clientId, issued.credentialId());
            return issued;
        }
    }

    private IssuedCredential issueLocked(PartnerClient client) {
        String credentialId = "cred-" + randomHex(8);
        String plainSecret = randomHex(24);
        ClientCredential credential = new ClientCredential(credentialId, hash(plainSecret), Instant.now(clock));
        client.getCredentials().add(credential);
        return new IssuedCredential(credentialId, plainSecret, credential);
    }

    /** 校验 clientId + secret，返回 null 表示成功，否则为可区分拒绝原因。 */
    public TokenRejectionReason authenticate(String clientId, String secret) {
        PartnerClient client = clients.get(clientId);
        if (client == null) {
            log.info("[partner] authenticate clientId={} -> UNKNOWN_CLIENT", clientId);
            return TokenRejectionReason.UNKNOWN_CLIENT;
        }
        if (client.isSuspended()) {
            log.info("[partner] authenticate clientId={} -> CLIENT_SUSPENDED", clientId);
            return TokenRejectionReason.CLIENT_SUSPENDED;
        }
        String secretHash = hash(secret);
        boolean sawRetiredMatch = false;
        Instant now = Instant.now(clock);
        for (ClientCredential c : client.getCredentials()) {
            if (!c.getSecretHash().equals(secretHash)) {
                continue;
            }
            switch (c.getStatus()) {
                case ACTIVE -> {
                    log.info("[partner] authenticate clientId={} credentialId={} ACTIVE -> 通过", clientId, c.getCredentialId());
                    return null;
                }
                case GRACE -> {
                    if (now.isBefore(c.getGraceUntil())) {
                        log.info("[partner] authenticate clientId={} credentialId={} GRACE 宽限期内 -> 通过", clientId, c.getCredentialId());
                        return null;
                    }
                    // 宽限期已过，惰性转为 RETIRED
                    c.setStatus(CredentialStatus.RETIRED);
                    sawRetiredMatch = true;
                }
                case RETIRED -> sawRetiredMatch = true;
            }
        }
        TokenRejectionReason reason = sawRetiredMatch ? TokenRejectionReason.CREDENTIAL_RETIRED : TokenRejectionReason.BAD_CREDENTIAL;
        log.info("[partner] authenticate clientId={} -> {}", clientId, reason);
        return reason;
    }

    public Optional<PartnerClient> find(String clientId) {
        return Optional.ofNullable(clients.get(clientId));
    }

    static String hash(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
