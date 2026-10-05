package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentService;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentTokenService;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import com.github.lisama569.openbankingconsentgatewayjava.service.RateLimiter;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenRejectionReason;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 授权绑定凭证：换取、范围约束、撤销/过期/范围变更三类失效原因区分、幂等与并发。 */
class ConsentTokenServiceTest {
    private static final Logger log = LoggerFactory.getLogger(ConsentTokenServiceTest.class);

    private MutableClock clock;
    private ConsentService consents;
    private ConsentTokenService consentTokens;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        NotificationService notifications = new NotificationService(new RateLimiter(clock, 100, 100), clock, 3);
        consents = new ConsentService(notifications, clock);
        consentTokens = new ConsentTokenService(consents, clock);
    }

    private Consent authorizedConsent(String key, Set<String> scopes, Duration ttl) {
        Consent consent = consents.createConsent(key, "client-a", "user-1", scopes, ttl);
        consents.authorize(consent.getConsentId());
        return consent;
    }

    @Test
    void exchangeIssuesTokenBoundedByConsent() {
        Consent consent = authorizedConsent("k1", Set.of("accounts:read", "balances:read"), Duration.ofDays(1));
        var result = consentTokens.exchange(consent.getConsentId(), null, Duration.ofHours(1));
        log.info("[test] 判定: 换取成功={} scopes={} expiresAt={} consentExpiresAt={}",
                result.ok(), result.token().getScopes(), result.token().getExpiresAt(), consent.getExpiresAt());
        assertTrue(result.ok());
        assertEquals(consent.getScopes(), result.token().getScopes(), "默认取授权全量范围");
        assertTrue(result.token().getExpiresAt().isBefore(consent.getExpiresAt()));

        TokenValidationResult valid = consentTokens.validate(result.token().getTokenId(), Set.of("accounts:read"));
        log.info("[test] 判定: 校验 valid={}", valid.valid());
        assertTrue(valid.valid());

        // 凭证有效期不得超过授权到期时间：ttl 给 10 天，授权只有 1 天
        Consent shortConsent = authorizedConsent("k1b", Set.of("accounts:read"), Duration.ofDays(1));
        var capped = consentTokens.exchange(shortConsent.getConsentId(), null, Duration.ofDays(10));
        log.info("[test] 判定: 凭证有效期被授权截断 tokenExpiresAt={} consentExpiresAt={}",
                capped.token().getExpiresAt(), shortConsent.getExpiresAt());
        assertEquals(shortConsent.getExpiresAt(), capped.token().getExpiresAt());
    }

    @Test
    void exchangeRejectsUnauthorizedConsentAndExcessiveScopes() {
        Consent pending = consents.createConsent("k2", "client-a", "user-1", Set.of("accounts:read"), Duration.ofDays(1));
        var notAuthorized = consentTokens.exchange(pending.getConsentId(), null, Duration.ofHours(1));
        log.info("[test] 判定: 未确认授权换取 -> {}", notAuthorized.reason());
        assertFalse(notAuthorized.ok());
        assertEquals(TokenRejectionReason.CONSENT_NOT_AUTHORIZED, notAuthorized.reason());

        Consent consent = authorizedConsent("k2b", Set.of("accounts:read"), Duration.ofDays(1));
        var excessive = consentTokens.exchange(consent.getConsentId(),
                Set.of("accounts:read", "payments:write"), Duration.ofHours(1));
        log.info("[test] 判定: 超出授权范围换取 -> {}", excessive.reason());
        assertFalse(excessive.ok());
        assertEquals(TokenRejectionReason.SCOPE_NOT_GRANTED, excessive.reason());
    }

    @Test
    void invalidationDistinguishesRevokedExpiredAndScopeChanged() {
        Consent toRevoke = authorizedConsent("k3a", Set.of("accounts:read"), Duration.ofDays(1));
        Consent toExpire = authorizedConsent("k3b", Set.of("accounts:read"), Duration.ofMinutes(5));
        Consent toChange = authorizedConsent("k3c", Set.of("accounts:read"), Duration.ofDays(1));
        Consent control = authorizedConsent("k3d", Set.of("accounts:read"), Duration.ofDays(1));

        var tRevoke = consentTokens.exchange(toRevoke.getConsentId(), null, Duration.ofHours(1)).token();
        var tExpire = consentTokens.exchange(toExpire.getConsentId(), null, Duration.ofHours(1)).token();
        var tChange = consentTokens.exchange(toChange.getConsentId(), null, Duration.ofHours(1)).token();
        var tControl = consentTokens.exchange(control.getConsentId(), null, Duration.ofHours(1)).token();
        log.info("[test] 输入: 4 条授权各换出凭证 revoke={} expire={} change={} control={}",
                tRevoke.getTokenId(), tExpire.getTokenId(), tChange.getTokenId(), tControl.getTokenId());

        // 撤销
        consents.revoke(toRevoke.getConsentId());
        // 自然到期
        clock.advance(Duration.ofMinutes(6));
        // 范围变更（需重新确认）
        consents.changeScopes(toChange.getConsentId(), Set.of("accounts:read", "payments:write"), Duration.ofDays(1));

        TokenValidationResult rRevoke = consentTokens.validate(tRevoke.getTokenId(), Set.of("accounts:read"));
        TokenValidationResult rExpire = consentTokens.validate(tExpire.getTokenId(), Set.of("accounts:read"));
        TokenValidationResult rChange = consentTokens.validate(tChange.getTokenId(), Set.of("accounts:read"));
        TokenValidationResult rControl = consentTokens.validate(tControl.getTokenId(), Set.of("accounts:read"));
        log.info("[test] 判定: 撤销->{} 到期->{} 范围变更->{} 对照->valid={}",
                rRevoke.reason(), rExpire.reason(), rChange.reason(), rControl.valid());

        assertEquals(TokenRejectionReason.CONSENT_REVOKED, rRevoke.reason(), "撤销须可区分");
        assertEquals(TokenRejectionReason.CONSENT_EXPIRED, rExpire.reason(), "到期须可区分");
        assertEquals(TokenRejectionReason.CONSENT_SCOPE_CHANGED, rChange.reason(), "范围变更须可区分");
        assertTrue(rControl.valid(), "未受影响的授权凭证仍有效");

        // 范围变更后即使重新确认，旧版本凭证也不复活（需重新换取）
        consents.authorize(toChange.getConsentId());
        TokenValidationResult afterReconfirm = consentTokens.validate(tChange.getTokenId(), Set.of("accounts:read"));
        log.info("[test] 判定: 重新确认后旧凭证 -> {}（授权状态={}）",
                afterReconfirm.reason(), consents.find(toChange.getConsentId()).orElseThrow().getStatus());
        assertEquals(TokenRejectionReason.CONSENT_SCOPE_CHANGED, afterReconfirm.reason());

        // 重新确认后可换出新版本凭证
        var renewed = consentTokens.exchange(toChange.getConsentId(), null, Duration.ofHours(1));
        log.info("[test] 判定: 重新换取 ok={} version={}", renewed.ok(), renewed.token().getConsentVersion());
        assertTrue(renewed.ok());
        assertEquals(1, renewed.token().getConsentVersion());
        assertTrue(consentTokens.validate(renewed.token().getTokenId(), Set.of("payments:write")).valid());
    }

    @Test
    void duplicateAndConcurrentExchangeYieldsSingleToken() throws InterruptedException {
        Consent consent = authorizedConsent("k4", Set.of("accounts:read"), Duration.ofDays(1));

        var first = consentTokens.exchange(consent.getConsentId(), null, Duration.ofHours(1));
        var second = consentTokens.exchange(consent.getConsentId(), null, Duration.ofHours(1));
        log.info("[test] 判定: 重复换取 tokenId 一致={}", first.token().getTokenId().equals(second.token().getTokenId()));
        assertSame(first.token(), second.token(), "重复换取必须返回同一凭证");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> tokenIds = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    tokenIds.add(consentTokens.exchange(consent.getConsentId(), null, Duration.ofHours(1))
                            .token().getTokenId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        Consent after = consents.find(consent.getConsentId()).orElseThrow();
        log.info("[test] 判定: {} 并发换取产生 {} 个不同凭证，授权状态={} version={}",
                threads, tokenIds.size(), after.getStatus(), after.getVersion());
        assertEquals(1, tokenIds.size(), "并发换取不得产生多份凭证");
        assertEquals(first.token().getTokenId(), tokenIds.iterator().next());
        assertEquals(ConsentStatus.AUTHORIZED, after.getStatus(), "换取不得改变授权状态");
        assertEquals(0, after.getVersion(), "换取不得改变授权版本");
    }
}
