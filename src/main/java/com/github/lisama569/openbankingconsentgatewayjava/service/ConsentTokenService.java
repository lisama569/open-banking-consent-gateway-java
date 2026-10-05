package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentAccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 围绕用户授权换取访问凭证。
 *
 * 语义：
 * - 仅对已确认（AUTHORIZED）的授权可换取凭证，凭证范围不得超过授权确认时的范围，
 *   凭证有效期不超过授权自身的到期时间；
 * - 同一授权同一版本重复/并发换取返回同一凭证（幂等，不会换出多份）；
 * - 授权撤销 / 过期 / 范围变更（版本变化或回到待确认）后，已换出的凭证立即失效，
 *   校验时分别返回 CONSENT_REVOKED / CONSENT_EXPIRED / CONSENT_SCOPE_CHANGED。
 */
@Service
public class ConsentTokenService {
    private static final Logger log = LoggerFactory.getLogger(ConsentTokenService.class);

    /** 换取结果：成功携带凭证，失败携带可区分原因。 */
    public record ExchangeResult(boolean ok, ConsentAccessToken token, TokenRejectionReason reason) {
        static ExchangeResult ok(ConsentAccessToken token) {
            return new ExchangeResult(true, token, null);
        }
        static ExchangeResult reject(TokenRejectionReason reason) {
            return new ExchangeResult(false, null, reason);
        }
    }

    private final Map<String, ConsentAccessToken> tokens = new ConcurrentHashMap<>();
    /** 幂等索引：consentId|consentVersion -> tokenId，保证同一授权版本只换出一份凭证 */
    private final Map<String, String> exchangeIndex = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ConsentService consentService;
    private final Clock clock;

    @Autowired
    public ConsentTokenService(ConsentService consentService) {
        this(consentService, Clock.systemUTC());
    }

    public ConsentTokenService(ConsentService consentService, Clock clock) {
        this.consentService = consentService;
        this.clock = clock;
    }

    /**
     * 针对一条已确认授权换取访问凭证。
     * requestedScopes 为 null 或空时取授权全量范围；超出授权范围返回 SCOPE_NOT_GRANTED。
     */
    public ExchangeResult exchange(String consentId, Set<String> requestedScopes, Duration ttl) {
        Consent consent = consentService.find(consentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown consent: " + consentId));
        synchronized (consent) {
            switch (consent.getStatus()) {
                case REVOKED -> {
                    log.info("[consent-token] exchange consentId={} -> CONSENT_REVOKED", consentId);
                    return ExchangeResult.reject(TokenRejectionReason.CONSENT_REVOKED);
                }
                case EXPIRED -> {
                    log.info("[consent-token] exchange consentId={} -> CONSENT_EXPIRED", consentId);
                    return ExchangeResult.reject(TokenRejectionReason.CONSENT_EXPIRED);
                }
                case PENDING_CONFIRMATION, REJECTED -> {
                    log.info("[consent-token] exchange consentId={} status={} -> CONSENT_NOT_AUTHORIZED",
                            consentId, consent.getStatus());
                    return ExchangeResult.reject(TokenRejectionReason.CONSENT_NOT_AUTHORIZED);
                }
                default -> { }
            }
            Set<String> scopes = (requestedScopes == null || requestedScopes.isEmpty())
                    ? consent.getScopes() : Set.copyOf(requestedScopes);
            if (!consent.getScopes().containsAll(scopes)) {
                log.info("[consent-token] exchange consentId={} requested={} granted={} -> SCOPE_NOT_GRANTED",
                        consentId, scopes, consent.getScopes());
                return ExchangeResult.reject(TokenRejectionReason.SCOPE_NOT_GRANTED);
            }
            String indexKey = consentId + "|" + consent.getVersion();
            String existingId = exchangeIndex.get(indexKey);
            if (existingId != null) {
                log.info("[consent-token] exchange 幂等命中 consentId={} version={} -> tokenId={}",
                        consentId, consent.getVersion(), existingId);
                return ExchangeResult.ok(tokens.get(existingId));
            }
            Instant now = Instant.now(clock);
            // 凭证有效期不超过授权自身的到期时间
            Instant expiresAt = now.plus(ttl).isBefore(consent.getExpiresAt())
                    ? now.plus(ttl) : consent.getExpiresAt();
            byte[] buf = new byte[24];
            random.nextBytes(buf);
            String tokenId = "ctok-" + HexFormat.of().formatHex(buf);
            ConsentAccessToken token = new ConsentAccessToken(tokenId, consentId, consent.getClientId(),
                    scopes, consent.getVersion(), now, expiresAt);
            tokens.put(tokenId, token);
            exchangeIndex.put(indexKey, tokenId);
            log.info("[consent-token] exchange consentId={} version={} scopes={} expiresAt={} -> tokenId={}",
                    consentId, consent.getVersion(), scopes, expiresAt, tokenId);
            return ExchangeResult.ok(token);
        }
    }

    /**
     * 校验绑定授权的凭证：先判定授权当前状态（撤销/过期/范围变更），再判定凭证自身有效期与范围。
     */
    public TokenValidationResult validate(String tokenId, Set<String> requiredScopes) {
        ConsentAccessToken token = tokens.get(tokenId);
        if (token == null) {
            log.info("[consent-token] validate tokenId={} -> TOKEN_NOT_FOUND", tokenId);
            return TokenValidationResult.reject(TokenRejectionReason.TOKEN_NOT_FOUND);
        }
        Consent consent = consentService.find(token.getConsentId()).orElseThrow();
        synchronized (consent) {
            if (consent.getStatus() == ConsentStatus.REVOKED) {
                log.info("[consent-token] validate tokenId={} consentId={} -> CONSENT_REVOKED",
                        tokenId, consent.getConsentId());
                return TokenValidationResult.reject(TokenRejectionReason.CONSENT_REVOKED);
            }
            if (consent.getStatus() == ConsentStatus.EXPIRED) {
                log.info("[consent-token] validate tokenId={} consentId={} -> CONSENT_EXPIRED",
                        tokenId, consent.getConsentId());
                return TokenValidationResult.reject(TokenRejectionReason.CONSENT_EXPIRED);
            }
            if (consent.getVersion() != token.getConsentVersion()
                    || consent.getStatus() != ConsentStatus.AUTHORIZED) {
                log.info("[consent-token] validate tokenId={} consentId={} tokenVersion={} consentVersion={} status={} -> CONSENT_SCOPE_CHANGED",
                        tokenId, consent.getConsentId(), token.getConsentVersion(),
                        consent.getVersion(), consent.getStatus());
                return TokenValidationResult.reject(TokenRejectionReason.CONSENT_SCOPE_CHANGED);
            }
        }
        if (!Instant.now(clock).isBefore(token.getExpiresAt())) {
            log.info("[consent-token] validate tokenId={} expiresAt={} -> TOKEN_EXPIRED", tokenId, token.getExpiresAt());
            return TokenValidationResult.reject(TokenRejectionReason.TOKEN_EXPIRED);
        }
        if (requiredScopes != null && !token.getScopes().containsAll(requiredScopes)) {
            log.info("[consent-token] validate tokenId={} required={} actual={} -> SCOPE_NOT_GRANTED",
                    tokenId, requiredScopes, token.getScopes());
            return TokenValidationResult.reject(TokenRejectionReason.SCOPE_NOT_GRANTED);
        }
        log.info("[consent-token] validate tokenId={} consentId={} -> OK", tokenId, token.getConsentId());
        return TokenValidationResult.ok(token);
    }

    /** 是否为本人签发的绑定授权凭证（供入口路由用）。 */
    public boolean isConsentToken(String tokenId) {
        return tokens.containsKey(tokenId);
    }
}
