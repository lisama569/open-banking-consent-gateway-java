package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.service.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 授权绑定凭证：换取幂等、并发稳定；撤销/到期/范围变更三类失效原因严格区分。 */
class ConsentBoundTokenServiceTest {
    private static final Logger log = LoggerFactory.getLogger(ConsentBoundTokenServiceTest.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T00:00:00Z"));

    private record Harness(NotificationService notifications, ConsentService consents, TokenService tokens) {}

    private Harness harness() {
        NotificationService notifications = new NotificationService(new RateLimiter(clock, 1000, 1000), clock, 3);
        ConsentService consents = new ConsentService(notifications, clock);
        TokenService tokens = new TokenService(consents, clock);
        return new Harness(notifications, consents, tokens);
    }

    private Consent authorizedConsent(Harness h, String idem, Set<String> scopes) {
        Consent consent = h.consents().createConsent(idem, "client-a", "user-1", scopes, Duration.ofDays(1));
        h.consents().authorize(consent.getConsentId());
        return consent;
    }

    @Test
    void revokeImmediatelyInvalidatesBoundToken() {
        Harness h = harness();
        Consent consent = authorizedConsent(h, "idem-revoke", Set.of("accounts:read"));
        AccessToken token = h.tokens().exchangeForConsent("client-a", consent.getConsentId(),
                Set.of("accounts:read"), Duration.ofHours(1)).token();
        log.info("[test] 输入: 授权换凭证 tokenId={} version={}", token.getTokenId(), token.getConsentVersion());
        assertTrue(h.tokens().validate(token.getTokenId(), Set.of()).valid());

        h.consents().revoke(consent.getConsentId());
        TokenValidationResult result = h.tokens().validate(token.getTokenId(), Set.of("accounts:read"));
        log.info("[test] 判定: 撤销后 reason={}", result.reason());
        assertFalse(result.valid());
        assertEquals(TokenRejectionReason.CONSENT_REVOKED, result.reason());
    }

    @Test
    void expiryImmediatelyInvalidatesBoundToken() {
        Harness h = harness();
        Consent consent = authorizedConsent(h, "idem-expire", Set.of("accounts:read"));
        AccessToken token = h.tokens().exchangeForConsent("client-a", consent.getConsentId(),
                Set.of(), Duration.ofHours(10)).token();
        log.info("[test] 输入: 授权 ttl=1d，凭证 ttl=10h，tokenId={}", token.getTokenId());

        clock.advance(Duration.ofHours(25)); // 授权先到期
        TokenValidationResult result = h.tokens().validate(token.getTokenId(), Set.of());
        log.info("[test] 判定: 授权到期（凭证自身未到期）reason={}", result.reason());
        assertEquals(TokenRejectionReason.CONSENT_EXPIRED, result.reason());
    }

    @Test
    void scopeChangeInvalidatesBoundTokenWithReconfirmationReason() {
        Harness h = harness();
        Consent consent = authorizedConsent(h, "idem-scope", Set.of("accounts:read"));
        AccessToken token = h.tokens().exchangeForConsent("client-a", consent.getConsentId(),
                Set.of("accounts:read"), Duration.ofHours(1)).token();

        // 范围变更 -> 回到待确认
        h.consents().changeScopes(consent.getConsentId(), Set.of("accounts:read", "payments:write"), Duration.ofDays(1));
        TokenValidationResult pendingResult = h.tokens().validate(token.getTokenId(), Set.of());
        log.info("[test] 判定: 范围变更未重新确认 reason={}（须与撤销/到期不同）", pendingResult.reason());
        assertEquals(TokenRejectionReason.CONSENT_SCOPE_RECONFIRMATION_REQUIRED, pendingResult.reason());

        // 用户重新确认后旧凭证仍然失效（它绑定的是旧版本）
        h.consents().authorize(consent.getConsentId());
        TokenValidationResult reconfirmed = h.tokens().validate(token.getTokenId(), Set.of());
        log.info("[test] 判定: 重新确认后旧凭证 reason={}（版本仍不匹配）", reconfirmed.reason());
        assertEquals(TokenRejectionReason.CONSENT_SCOPE_RECONFIRMATION_REQUIRED, reconfirmed.reason());

        // 新版本授权可换出新凭证
        AccessToken newToken = h.tokens().exchangeForConsent("client-a", consent.getConsentId(),
                Set.of("accounts:read"), Duration.ofHours(1)).token();
        // 注意：旧版本换过凭证后，新版本换取应签发新凭证
        log.info("[test] 判定: 新版本换取 tokenId 旧={} 新={}", token.getTokenId(), newToken.getTokenId());
        assertNotEquals(token.getTokenId(), newToken.getTokenId());
        assertTrue(h.tokens().validate(newToken.getTokenId(), Set.of("accounts:read")).valid());
    }

    @Test
    void requestedScopesCannotExceedConsentScopes() {
        Harness h = harness();
        Consent consent = authorizedConsent(h, "idem-exceed", Set.of("accounts:read"));
        TokenValidationResult result = h.tokens().exchangeForConsent("client-a", consent.getConsentId(),
                Set.of("accounts:read", "payments:write"), Duration.ofHours(1));
        log.info("[test] 输入: 请求范围超出授权 -> reason={}", result.reason());
        assertEquals(TokenRejectionReason.CONSENT_SCOPE_EXCEEDED, result.reason());
    }

    @Test
    void exchangeBeforeUserConfirmationIsRejected() {
        Harness h = harness();
        Consent consent = h.consents().createConsent("idem-pending", "client-a", "user-1",
                Set.of("accounts:read"), Duration.ofDays(1));
        TokenValidationResult result = h.tokens().exchangeForConsent("client-a", consent.getConsentId(),
                Set.of(), Duration.ofHours(1));
        log.info("[test] 判定: 未确认授权换取 reason={}", result.reason());
        assertEquals(TokenRejectionReason.CONSENT_NOT_AUTHORIZED, result.reason());
    }

    @Test
    void repeatedAndConcurrentExchangeReturnsSingleStableToken() throws Exception {
        Harness h = harness();
        Consent consent = authorizedConsent(h, "idem-concurrent", Set.of("accounts:read", "payments:read"));

        // 顺序重复换取：同一份
        AccessToken first = h.tokens().exchangeForConsent("client-a", consent.getConsentId(),
                Set.of("accounts:read"), Duration.ofHours(1)).token();
        AccessToken second = h.tokens().exchangeForConsent("client-a", consent.getConsentId(),
                Set.of("accounts:read"), Duration.ofHours(1)).token();
        assertSame(first, second, "重复换取不得换出第二份凭证");
        log.info("[test] 判定: 重复换取稳定返回同一 tokenId={}", first.getTokenId());

        // 并发换取：仍然只有一份
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> tokenIds = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    AccessToken t = h.tokens().exchangeForConsent("client-a", consent.getConsentId(),
                            Set.of("accounts:read"), Duration.ofHours(1)).token();
                    tokenIds.add(t.getTokenId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        log.info("[test] 判定: 16 线程并发换取结果 tokenIds={}", tokenIds);
        assertEquals(Set.of(first.getTokenId()), tokenIds);

        // 授权状态不得因换取而变化
        assertEquals(0L, consent.getVersion());
        assertEquals(com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus.AUTHORIZED,
                h.consents().find(consent.getConsentId()).orElseThrow().getStatus());
        // 授权下事件仍只有 AUTHORIZED 一个（换取不发事件）
        long eventCount = h.notifications().eventsFor(consent.getConsentId()).size();
        log.info("[test] 判定: 换取后授权事件数={}（换取本身不得发布事件）", eventCount);
        assertEquals(1, eventCount);
    }

    @Test
    void unboundLegacyTokenUnaffectedByConsentLifecycle() {
        Harness h = harness();
        Consent consent = authorizedConsent(h, "idem-legacy", Set.of("accounts:read"));
        AccessToken legacy = h.tokens().issue("client-a", Set.of("accounts:read"), Duration.ofHours(10));
        h.consents().revoke(consent.getConsentId());
        TokenValidationResult result = h.tokens().validate(legacy.getTokenId(), Set.of("accounts:read"));
        log.info("[test] 判定: 旧入口令牌在授权撤销后 valid={} reason={}", result.valid(), result.reason());
        assertTrue(result.valid(), "不绑定授权的旧凭证不受授权生命周期影响");
        assertFalse(legacy.isConsentBound());
    }
}
