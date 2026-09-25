package com.github.lisama569.openbankingconsentgatewayjava.tests;

import com.github.lisama569.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.TokenRejectionReason;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenService;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenValidationResult;
import com.github.lisama569.openbankingconsentgatewayjava.store.ConsentStore;
import com.github.lisama569.openbankingconsentgatewayjava.store.TokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 令牌签发与校验：过期、撤销、越权等拒绝原因须可区分。 */
class TokenServiceTest {
    private static final Logger log = LoggerFactory.getLogger(TokenServiceTest.class);

    private MutableClock clock;
    private TokenStore tokenStore;
    private ConsentStore consentStore;
    private TokenService tokenService;
    private GatewayProperties properties;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        tokenStore = new TokenStore();
        consentStore = new ConsentStore();
        properties = new GatewayProperties();
        properties.setTokenTtlSeconds(60);
        tokenService = new TokenService(tokenStore, consentStore, properties, clock);
    }

    private Consent activeConsent(String consentId, Set<String> scopes, long ttlSeconds) {
        Consent consent = new Consent(consentId, "client-1", "user-1", scopes,
                ConsentStatus.ACTIVE, clock.instant(), clock.instant().plusSeconds(ttlSeconds), 1);
        consentStore.save(consent);
        return consent;
    }

    @Test
    void validateAcceptsValidToken() {
        activeConsent("con-1", Set.of("accounts", "payments"), 3600);
        AccessToken token = tokenService.issue("con-1", "client-1", Set.of("accounts"));
        TokenValidationResult result = tokenService.validate(token.getTokenId(), "client-1", Set.of("accounts"));
        log.info("case=valid tokenId={} accepted={}", token.getTokenId(), result.accepted());
        assertTrue(result.accepted());
    }

    @Test
    void rejectsExpiredToken() {
        activeConsent("con-1", Set.of("accounts"), 3600);
        AccessToken token = tokenService.issue("con-1", "client-1", Set.of("accounts"));
        clock.advanceSeconds(61); // 超过 tokenTtl=60s
        TokenValidationResult result = tokenService.validate(token.getTokenId(), "client-1", Set.of("accounts"));
        log.info("case=expired tokenId={} reason={}", token.getTokenId(), result.rejectionReason());
        assertFalse(result.accepted());
        assertEquals(TokenRejectionReason.TOKEN_EXPIRED, result.rejectionReason());
    }

    @Test
    void rejectsRevokedToken() {
        activeConsent("con-1", Set.of("accounts"), 3600);
        AccessToken token = tokenService.issue("con-1", "client-1", Set.of("accounts"));
        tokenService.revokeByConsent("con-1");
        TokenValidationResult result = tokenService.validate(token.getTokenId(), "client-1", Set.of("accounts"));
        log.info("case=revoked tokenId={} reason={}", token.getTokenId(), result.rejectionReason());
        assertFalse(result.accepted());
        assertEquals(TokenRejectionReason.TOKEN_REVOKED, result.rejectionReason());
    }

    @Test
    void rejectsTokenWhenConsentRevoked() {
        Consent consent = activeConsent("con-1", Set.of("accounts"), 3600);
        AccessToken token = tokenService.issue("con-1", "client-1", Set.of("accounts"));
        consent.setStatus(ConsentStatus.REVOKED);
        TokenValidationResult result = tokenService.validate(token.getTokenId(), "client-1", Set.of("accounts"));
        log.info("case=consent-revoked tokenId={} reason={}", token.getTokenId(), result.rejectionReason());
        assertFalse(result.accepted());
        assertEquals(TokenRejectionReason.CONSENT_REVOKED, result.rejectionReason());
    }

    @Test
    void rejectsTokenWhenConsentExpired() {
        activeConsent("con-1", Set.of("accounts"), 30);
        properties.setTokenTtlSeconds(3600); // 令牌有效期长于授权
        AccessToken token = tokenService.issue("con-1", "client-1", Set.of("accounts"));
        clock.advanceSeconds(31); // 授权过期但令牌未过期
        TokenValidationResult result = tokenService.validate(token.getTokenId(), "client-1", Set.of("accounts"));
        log.info("case=consent-expired tokenId={} reason={}", token.getTokenId(), result.rejectionReason());
        assertFalse(result.accepted());
        assertEquals(TokenRejectionReason.CONSENT_EXPIRED, result.rejectionReason());
    }

    @Test
    void rejectsInsufficientScope() {
        activeConsent("con-1", Set.of("accounts"), 3600);
        AccessToken token = tokenService.issue("con-1", "client-1", Set.of("accounts"));
        TokenValidationResult result = tokenService.validate(token.getTokenId(), "client-1", Set.of("payments"));
        log.info("case=insufficient-scope tokenId={} reason={}", token.getTokenId(), result.rejectionReason());
        assertFalse(result.accepted());
        assertEquals(TokenRejectionReason.INSUFFICIENT_SCOPE, result.rejectionReason());
    }

    @Test
    void rejectsUnknownTokenAndForeignClient() {
        TokenValidationResult unknown = tokenService.validate("tok-missing", "client-1", Set.of());
        log.info("case=unknown-token reason={}", unknown.rejectionReason());
        assertEquals(TokenRejectionReason.UNKNOWN_TOKEN, unknown.rejectionReason());

        activeConsent("con-1", Set.of("accounts"), 3600);
        AccessToken token = tokenService.issue("con-1", "client-1", Set.of("accounts"));
        TokenValidationResult foreign = tokenService.validate(token.getTokenId(), "client-2", Set.of("accounts"));
        log.info("case=foreign-client reason={}", foreign.rejectionReason());
        assertEquals(TokenRejectionReason.UNKNOWN_TOKEN, foreign.rejectionReason());
    }

    @Test
    void issueRejectsScopesBeyondConsent() {
        activeConsent("con-1", Set.of("accounts"), 3600);
        assertThrows(IllegalArgumentException.class,
                () -> tokenService.issue("con-1", "client-1", Set.of("accounts", "payments")));
    }
}
