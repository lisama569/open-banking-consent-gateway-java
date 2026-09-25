package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenRejectionReason;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 令牌签发与校验：过期、撤销、越权的拒绝原因须可区分。 */
class TokenServiceTest {
    private static final Logger log = LoggerFactory.getLogger(TokenServiceTest.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
    private final TokenService tokens = new TokenService(clock);

    @Test
    void validateDistinguishesExpiryRevocationAndScope() {
        AccessToken token = tokens.issue("client-a", Set.of("accounts:read"), Duration.ofMinutes(10));
        log.info("[test] 输入: tokenId={} scopes={} ttl=10min", token.getTokenId(), token.getScopes());

        var ok = tokens.validate(token.getTokenId(), Set.of("accounts:read"));
        log.info("[test] 判定: 有效令牌 -> valid={}", ok.valid());
        assertTrue(ok.valid());

        var forbidden = tokens.validate(token.getTokenId(), Set.of("payments:write"));
        log.info("[test] 判定: 越权访问 required=payments:write -> reason={}", forbidden.reason());
        assertFalse(forbidden.valid());
        assertEquals(TokenRejectionReason.SCOPE_NOT_GRANTED, forbidden.reason());

        tokens.revoke(token.getTokenId());
        var revoked = tokens.validate(token.getTokenId(), Set.of("accounts:read"));
        log.info("[test] 判定: 撤销后 -> reason={}", revoked.reason());
        assertEquals(TokenRejectionReason.TOKEN_REVOKED, revoked.reason());

        AccessToken shortLived = tokens.issue("client-a", Set.of("accounts:read"), Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(31));
        var expired = tokens.validate(shortLived.getTokenId(), Set.of("accounts:read"));
        log.info("[test] 判定: 过期后 -> reason={}", expired.reason());
        assertEquals(TokenRejectionReason.TOKEN_EXPIRED, expired.reason());

        var unknown = tokens.validate("tok-nonexistent", Set.of());
        log.info("[test] 判定: 未知令牌 -> reason={}", unknown.reason());
        assertEquals(TokenRejectionReason.TOKEN_NOT_FOUND, unknown.reason());
    }
}
