package com.github.lisama569.openbankingconsentgatewayjava.tests;

import com.github.lisama569.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.TokenRejectionReason;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentService;
import com.github.lisama569.openbankingconsentgatewayjava.service.LocalNotificationSink;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenService;
import com.github.lisama569.openbankingconsentgatewayjava.store.ConsentStore;
import com.github.lisama569.openbankingconsentgatewayjava.store.DeliveryStore;
import com.github.lisama569.openbankingconsentgatewayjava.store.EventStore;
import com.github.lisama569.openbankingconsentgatewayjava.store.TokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** 授权生命周期：幂等、并发、撤销与过期、范围变更确认。 */
class ConsentServiceTest {
    private static final Logger log = LoggerFactory.getLogger(ConsentServiceTest.class);

    private MutableClock clock;
    private ConsentStore consentStore;
    private EventStore eventStore;
    private TokenService tokenService;
    private ConsentService consentService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        consentStore = new ConsentStore();
        eventStore = new EventStore();
        TokenStore tokenStore = new TokenStore();
        DeliveryStore deliveryStore = new DeliveryStore();
        GatewayProperties properties = new GatewayProperties();
        tokenService = new TokenService(tokenStore, consentStore, properties, clock);
        NotificationService notificationService = new NotificationService(
                eventStore, deliveryStore, properties, new LocalNotificationSink(), clock);
        consentService = new ConsentService(consentStore, eventStore, tokenService,
                notificationService, properties, clock);
    }

    @Test
    void duplicateGrantWithSameKeyIsIdempotent() {
        Consent first = consentService.grant("client-1", "user-1", Set.of("accounts"), "key-1", null);
        Consent second = consentService.grant("client-1", "user-1", Set.of("accounts"), "key-1", null);
        log.info("case=duplicate-grant first={} second={} events={}",
                first.getConsentId(), second.getConsentId(), eventStore.findByConsentId(first.getConsentId()).size());
        assertSame(first, second);
        assertEquals(1, eventStore.findByConsentId(first.getConsentId()).size(), "重复提交不得产生重复事件");
    }

    @Test
    void concurrentDuplicateGrantYieldsSingleConsentAndSingleEvent() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Consent>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return consentService.grant("client-1", "user-1", Set.of("accounts"), "key-race", null);
            }));
        }
        start.countDown();
        List<Consent> results = new ArrayList<>();
        for (Future<Consent> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        Consent first = results.get(0);
        long distinct = results.stream().map(Consent::getConsentId).distinct().count();
        int events = eventStore.findByConsentId(first.getConsentId()).size();
        log.info("case=concurrent-grant threads={} distinctConsents={} events={} status={}",
                threads, distinct, events, first.getStatus());
        assertEquals(1, distinct, "并发相同请求不得产生重复授权");
        assertEquals(1, events, "并发相同请求不得产生重复事件/通知");
        assertEquals(ConsentStatus.ACTIVE, first.getStatus());
    }

    @Test
    void revokeIsIdempotentAndCascadesToTokens() {
        Consent consent = consentService.grant("client-1", "user-1", Set.of("accounts"), "key-1", null);
        AccessToken token = tokenService.issue(consent.getConsentId(), "client-1", Set.of("accounts"));

        consentService.revoke(consent.getConsentId());
        var afterFirst = tokenService.validate(token.getTokenId(), "client-1", Set.of("accounts"));
        log.info("case=revoke status={} tokenRevoked={} validateReason={}",
                consent.getStatus(), token.isRevoked(), afterFirst.rejectionReason());
        assertEquals(ConsentStatus.REVOKED, consent.getStatus());
        assertTrue(token.isRevoked(), "撤销授权须级联撤销令牌，状态保持一致");
        assertEquals(TokenRejectionReason.TOKEN_REVOKED, afterFirst.rejectionReason());

        int eventsBefore = eventStore.findByConsentId(consent.getConsentId()).size();
        consentService.revoke(consent.getConsentId()); // 重复撤销幂等
        int eventsAfter = eventStore.findByConsentId(consent.getConsentId()).size();
        log.info("case=revoke-twice eventsBefore={} eventsAfter={} status={}", eventsBefore, eventsAfter, consent.getStatus());
        assertEquals(eventsBefore, eventsAfter, "重复撤销不得产生重复事件");
        assertEquals(ConsentStatus.REVOKED, consent.getStatus(), "状态不得回退");
    }

    @Test
    void expiredConsentRejectsTokenAndExpiresOnlyOnce() {
        Consent consent = consentService.grant("client-1", "user-1", Set.of("accounts"), "key-1", 30L);
        AccessToken token = tokenService.issue(consent.getConsentId(), "client-1", Set.of("accounts"));
        clock.advanceSeconds(31);

        consentService.expireIfDue(consent.getConsentId());
        var result = tokenService.validate(token.getTokenId(), "client-1", Set.of("accounts"));
        log.info("case=expired status={} tokenRevoked={} validateReason={}",
                consent.getStatus(), token.isRevoked(), result.rejectionReason());
        assertEquals(ConsentStatus.EXPIRED, consent.getStatus());
        assertTrue(token.isRevoked());
        assertEquals(TokenRejectionReason.TOKEN_REVOKED, result.rejectionReason());

        int eventsBefore = eventStore.findByConsentId(consent.getConsentId()).size();
        consentService.expireIfDue(consent.getConsentId());
        assertEquals(eventsBefore, eventStore.findByConsentId(consent.getConsentId()).size(),
                "过期事件只产生一次");
    }

    @Test
    void scopeChangeRequiresConfirmationAndIsIdempotentWhenUnchanged() {
        Consent consent = consentService.grant("client-1", "user-1", Set.of("accounts"), "key-1", null);
        assertThrows(IllegalStateException.class,
                () -> consentService.changeScopes(consent.getConsentId(), Set.of("accounts", "payments"), false),
                "范围变更必须重新确认");

        Consent unchanged = consentService.changeScopes(consent.getConsentId(), Set.of("accounts"), true);
        log.info("case=scope-noop version={} events={}", unchanged.getVersion(),
                eventStore.findByConsentId(consent.getConsentId()).size());
        assertEquals(1, unchanged.getVersion(), "范围相同为幂等无操作");

        AccessToken oldToken = tokenService.issue(consent.getConsentId(), "client-1", Set.of("accounts"));
        Consent changed = consentService.changeScopes(consent.getConsentId(), Set.of("accounts", "payments"), true);
        log.info("case=scope-changed version={} scopes={} oldTokenRevoked={}",
                changed.getVersion(), changed.getScopes(), oldToken.isRevoked());
        assertEquals(2, changed.getVersion());
        assertTrue(changed.getScopes().contains("payments"));
        assertTrue(oldToken.isRevoked(), "范围变更后旧令牌须撤销");
    }
}
