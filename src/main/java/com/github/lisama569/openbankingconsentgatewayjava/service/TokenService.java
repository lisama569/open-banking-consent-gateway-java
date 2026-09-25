package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.TokenRejectionReason;
import com.github.lisama569.openbankingconsentgatewayjava.store.ConsentStore;
import com.github.lisama569.openbankingconsentgatewayjava.store.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 令牌签发与校验，拒绝原因可区分。 */
@Service
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final TokenStore tokenStore;
    private final ConsentStore consentStore;
    private final GatewayProperties properties;
    private final Clock clock;

    @Autowired
    public TokenService(TokenStore tokenStore, ConsentStore consentStore, GatewayProperties properties) {
        this(tokenStore, consentStore, properties, Clock.systemUTC());
    }

    public TokenService(TokenStore tokenStore, ConsentStore consentStore, GatewayProperties properties, Clock clock) {
        this.tokenStore = tokenStore;
        this.consentStore = consentStore;
        this.properties = properties;
        this.clock = clock;
    }

    /** 基于有效授权签发令牌，令牌范围不得超过授权范围。 */
    public AccessToken issue(String consentId, String clientId, Set<String> scopes) {
        Consent consent = consentStore.findById(consentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown consent: " + consentId));
        if (!consent.getClientId().equals(clientId)) {
            throw new IllegalArgumentException("consent does not belong to client: " + clientId);
        }
        refreshExpiry(consent);
        if (consent.getStatus() != ConsentStatus.ACTIVE) {
            throw new IllegalStateException("consent not active: " + consent.getStatus());
        }
        if (!consent.getScopes().containsAll(scopes)) {
            throw new IllegalArgumentException("requested scopes exceed consent scopes");
        }
        Instant now = clock.instant();
        AccessToken token = new AccessToken("tok-" + UUID.randomUUID(), consentId, clientId,
                Set.copyOf(scopes), now, now.plusSeconds(properties.getTokenTtlSeconds()));
        tokenStore.save(token);
        log.info("issue consentId={} clientId={} scopes={} tokenId={} expiresAt={}",
                consentId, clientId, scopes, token.getTokenId(), token.getExpiresAt());
        return token;
    }

    /** 校验令牌：依次判定未知、撤销、过期、授权状态、越权。 */
    public TokenValidationResult validate(String tokenId, String clientId, Set<String> requiredScopes) {
        var tokenOpt = tokenStore.findById(tokenId);
        if (tokenOpt.isEmpty()) {
            log.info("validate tokenId={} clientId={} result=reject reason={}", tokenId, clientId, TokenRejectionReason.UNKNOWN_TOKEN);
            return TokenValidationResult.rejected(TokenRejectionReason.UNKNOWN_TOKEN);
        }
        AccessToken token = tokenOpt.get();
        if (!token.getClientId().equals(clientId)) {
            log.info("validate tokenId={} clientId={} result=reject reason={}", tokenId, clientId, TokenRejectionReason.UNKNOWN_TOKEN);
            return TokenValidationResult.rejected(TokenRejectionReason.UNKNOWN_TOKEN);
        }
        if (token.isRevoked()) {
            log.info("validate tokenId={} clientId={} result=reject reason={}", tokenId, clientId, TokenRejectionReason.TOKEN_REVOKED);
            return TokenValidationResult.rejected(TokenRejectionReason.TOKEN_REVOKED);
        }
        if (!clock.instant().isBefore(token.getExpiresAt())) {
            log.info("validate tokenId={} clientId={} expiresAt={} result=reject reason={}",
                    tokenId, clientId, token.getExpiresAt(), TokenRejectionReason.TOKEN_EXPIRED);
            return TokenValidationResult.rejected(TokenRejectionReason.TOKEN_EXPIRED);
        }
        Consent consent = consentStore.findById(token.getConsentId()).orElse(null);
        if (consent != null) {
            refreshExpiry(consent);
            if (consent.getStatus() == ConsentStatus.REVOKED) {
                log.info("validate tokenId={} clientId={} result=reject reason={}", tokenId, clientId, TokenRejectionReason.CONSENT_REVOKED);
                return TokenValidationResult.rejected(TokenRejectionReason.CONSENT_REVOKED);
            }
            if (consent.getStatus() == ConsentStatus.EXPIRED) {
                log.info("validate tokenId={} clientId={} result=reject reason={}", tokenId, clientId, TokenRejectionReason.CONSENT_EXPIRED);
                return TokenValidationResult.rejected(TokenRejectionReason.CONSENT_EXPIRED);
            }
        }
        if (!token.getScopes().containsAll(requiredScopes)) {
            log.info("validate tokenId={} clientId={} required={} held={} result=reject reason={}",
                    tokenId, clientId, requiredScopes, token.getScopes(), TokenRejectionReason.INSUFFICIENT_SCOPE);
            return TokenValidationResult.rejected(TokenRejectionReason.INSUFFICIENT_SCOPE);
        }
        log.info("validate tokenId={} clientId={} required={} result=accept", tokenId, clientId, requiredScopes);
        return TokenValidationResult.accepted(token);
    }

    /** 撤销授权时级联撤销其令牌，保持令牌与授权状态一致。 */
    public void revokeByConsent(String consentId) {
        // 本地存储下遍历即可；规模小且可解释。
        tokenStore.findAll().stream()
                .filter(t -> t.getConsentId().equals(consentId))
                .forEach(t -> {
                    t.setRevoked(true);
                    log.info("revokeByConsent consentId={} tokenId={} revoked=true", consentId, t.getTokenId());
                });
    }

    private void refreshExpiry(Consent consent) {
        if (consent.getStatus() == ConsentStatus.ACTIVE
                && !clock.instant().isBefore(consent.getExpiresAt())) {
            consent.setStatus(ConsentStatus.EXPIRED);
        }
    }
}
