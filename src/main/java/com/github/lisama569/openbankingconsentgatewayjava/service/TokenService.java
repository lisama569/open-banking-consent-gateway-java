package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
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

/** 访问令牌签发与校验，校验结果带可区分拒绝原因。 */
@Service
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final Map<String, AccessToken> tokens = new ConcurrentHashMap<>();
    /** consentId#version -> 基于该授权某一确认版本换出的唯一凭证；同版本重复/并发换取返回同一份。 */
    private final Map<String, AccessToken> tokensByConsent = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final ConsentService consentService;

    public TokenService() {
        this(null, Clock.systemUTC());
    }

    public TokenService(Clock clock) {
        this(null, clock);
    }

    @Autowired
    public TokenService(ConsentService consentService) {
        this(consentService, Clock.systemUTC());
    }

    public TokenService(ConsentService consentService, Clock clock) {
        this.consentService = consentService;
        this.clock = clock;
    }

    /** 认证成功后签发令牌。 */
    public AccessToken issue(String clientId, Set<String> scopes, Duration ttl) {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String tokenId = "tok-" + HexFormat.of().formatHex(buf);
        Instant now = Instant.now(clock);
        AccessToken token = new AccessToken(tokenId, clientId, scopes, now, now.plus(ttl));
        tokens.put(tokenId, token);
        log.info("[token] issue clientId={} scopes={} expiresAt={} consentBound=false", clientId, scopes, token.getExpiresAt());
        return token;
    }

    /**
     * 以一条已被用户确认的授权换取访问凭证。
     *
     * 语义：
     * - 授权必须存在且处于 AUTHORIZED；请求范围不能超过授权当前确认范围；
     * - 同一条授权重复/并发换取只产生一份凭证，后续换取稳定返回同一 token，
     *   且不改变授权本身的任何状态（不重新确认、不发事件）；
     * - 凭证范围在换取时固化为请求范围（请求为空则取授权全部范围），不超过授权范围。
     */
    public TokenValidationResult exchangeForConsent(String clientId, String consentId,
                                                    Set<String> requestedScopes, Duration ttl) {
        Set<String> requested = requestedScopes == null ? Set.of() : requestedScopes;
        if (consentService == null) {
            throw new IllegalStateException("consentService not wired");
        }
        // 与 authorize/revoke/changeScopes 共用 consentId 维度锁，换取与状态变更串行化
        synchronized (consentService.lockFor(consentId)) {
            Consent consent = consentService.rawConsent(consentId);
            if (consent == null) {
                log.info("[token] exchange consentId={} -> CONSENT_NOT_FOUND", consentId);
                return TokenValidationResult.reject(TokenRejectionReason.CONSENT_NOT_FOUND);
            }
            if (consent.getStatus() != ConsentStatus.AUTHORIZED) {
                TokenRejectionReason reason = switch (consent.getStatus()) {
                    case REVOKED -> TokenRejectionReason.CONSENT_REVOKED;
                    case EXPIRED -> TokenRejectionReason.CONSENT_EXPIRED;
                    case PENDING_CONFIRMATION, REJECTED -> TokenRejectionReason.CONSENT_NOT_AUTHORIZED;
                    case AUTHORIZED -> throw new AssertionError("unreachable");
                };
                log.info("[token] exchange consentId={} status={} -> {}", consentId, consent.getStatus(), reason);
                return TokenValidationResult.reject(reason);
            }
            if (clientId != null && !clientId.equals(consent.getClientId())) {
                log.info("[token] exchange consentId={} clientId={} 不属于授权 clientId={} -> CONSENT_NOT_FOUND",
                        consentId, clientId, consent.getClientId());
                return TokenValidationResult.reject(TokenRejectionReason.CONSENT_NOT_FOUND);
            }
            if (!consent.getScopes().containsAll(requested)) {
                log.info("[token] exchange consentId={} requested={} consentScopes={} -> CONSENT_SCOPE_EXCEEDED",
                        consentId, requested, consent.getScopes());
                return TokenValidationResult.reject(TokenRejectionReason.CONSENT_SCOPE_EXCEEDED);
            }
            String consentKey = consentId + "#" + consent.getVersion();
            AccessToken existing = tokensByConsent.get(consentKey);
            if (existing != null) {
                log.info("[token] exchange consentId={} version={} 幂等命中 -> tokenId={}",
                        consentId, consent.getVersion(), existing.getTokenId());
                return TokenValidationResult.ok(existing);
            }
            Set<String> granted = requested.isEmpty() ? consent.getScopes() : requested;
            byte[] buf = new byte[24];
            random.nextBytes(buf);
            String tokenId = "tok-" + HexFormat.of().formatHex(buf);
            Instant now = Instant.now(clock);
            AccessToken token = new AccessToken(tokenId, consent.getClientId(), granted, now, now.plus(ttl),
                    consentId, consent.getVersion());
            tokens.put(tokenId, token);
            tokensByConsent.put(consentKey, token);
            log.info("[token] exchange consentId={} version={} scopes={} -> tokenId={} consentBound=true",
                    consentId, consent.getVersion(), granted, tokenId);
            return TokenValidationResult.ok(token);
        }
    }

    /** 校验令牌并检查所需范围，返回可区分结果。 */
    public TokenValidationResult validate(String tokenId, Set<String> requiredScopes) {
        AccessToken token = tokens.get(tokenId);
        if (token == null) {
            log.info("[token] validate tokenId={} -> TOKEN_NOT_FOUND", tokenId);
            return TokenValidationResult.reject(TokenRejectionReason.TOKEN_NOT_FOUND);
        }
        if (token.isRevoked()) {
            log.info("[token] validate tokenId={} -> TOKEN_REVOKED", tokenId);
            return TokenValidationResult.reject(TokenRejectionReason.TOKEN_REVOKED);
        }
        // 绑定凭证：授权层面（撤销/到期/版本变化）优先于凭证自身过期判定，
        // 保证两类“过期”原因可区分，且授权撤销立即生效。
        if (token.isConsentBound()) {
            TokenValidationResult consentCheck = checkBoundConsent(token);
            if (consentCheck != null) {
                return consentCheck;
            }
        }
        if (!Instant.now(clock).isBefore(token.getExpiresAt())) {
            log.info("[token] validate tokenId={} expiresAt={} -> TOKEN_EXPIRED", tokenId, token.getExpiresAt());
            return TokenValidationResult.reject(TokenRejectionReason.TOKEN_EXPIRED);
        }
        if (requiredScopes != null && !token.getScopes().containsAll(requiredScopes)) {
            log.info("[token] validate tokenId={} required={} actual={} -> SCOPE_NOT_GRANTED",
                    tokenId, requiredScopes, token.getScopes());
            return TokenValidationResult.reject(TokenRejectionReason.SCOPE_NOT_GRANTED);
        }
        log.info("[token] validate tokenId={} -> OK consentBound={}", tokenId, token.isConsentBound());
        return TokenValidationResult.ok(token);
    }

    /**
     * 绑定授权的失效判定，原因严格区分：
     * 撤销 -> CONSENT_REVOKED；到期 -> CONSENT_EXPIRED；
     * 授权版本变化（范围已改动，需重新确认）-> CONSENT_SCOPE_RECONFIRMATION_REQUIRED。
     * 返回 null 表示授权层面仍有效。
     */
    private TokenValidationResult checkBoundConsent(AccessToken token) {
        String consentId = token.getConsentId();
        if (consentService == null) {
            return null;
        }
        // find 会触发惰性过期并在到期时把授权置为 EXPIRED，随后给出精确原因
        Consent consent = consentService.find(consentId).orElse(null);
        if (consent == null) {
            log.info("[token] validate tokenId={} consentId={} -> CONSENT_NOT_FOUND", token.getTokenId(), consentId);
            return TokenValidationResult.reject(TokenRejectionReason.CONSENT_NOT_FOUND);
        }
        switch (consent.getStatus()) {
            case REVOKED -> {
                log.info("[token] validate tokenId={} consentId={} -> CONSENT_REVOKED", token.getTokenId(), consentId);
                return TokenValidationResult.reject(TokenRejectionReason.CONSENT_REVOKED);
            }
            case EXPIRED -> {
                log.info("[token] validate tokenId={} consentId={} -> CONSENT_EXPIRED", token.getTokenId(), consentId);
                return TokenValidationResult.reject(TokenRejectionReason.CONSENT_EXPIRED);
            }
            default -> { }
        }
        if (consent.getVersion() != token.getConsentVersion()) {
            log.info("[token] validate tokenId={} consentId={} boundVersion={} currentVersion={} status={} -> CONSENT_SCOPE_RECONFIRMATION_REQUIRED",
                    token.getTokenId(), consentId, token.getConsentVersion(), consent.getVersion(), consent.getStatus());
            return TokenValidationResult.reject(TokenRejectionReason.CONSENT_SCOPE_RECONFIRMATION_REQUIRED);
        }
        return null;
    }

    public void revoke(String tokenId) {
        AccessToken token = tokens.get(tokenId);
        if (token != null) {
            token.setRevoked(true);
            log.info("[token] revoke tokenId={}", tokenId);
        }
    }
}
