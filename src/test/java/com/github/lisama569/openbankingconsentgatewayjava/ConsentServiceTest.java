package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEventType;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentService;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationRejectedException;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import com.github.lisama569.openbankingconsentgatewayjava.service.RateLimiter;
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

/** 授权生命周期：幂等创建、并发去重、撤销/过期、范围变更需重新确认。 */
class ConsentServiceTest {
    private static final Logger log = LoggerFactory.getLogger(ConsentServiceTest.class);

    private MutableClock clock;
    private NotificationService notifications;
    private ConsentService consents;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        notifications = new NotificationService(new RateLimiter(clock, 100, 100), clock, 3);
        consents = new ConsentService(notifications, clock);
    }

    @Test
    void duplicateCreateIsIdempotent() {
        Consent first = consents.createConsent("key-1", "client-a", "user-1", Set.of("accounts:read"), Duration.ofDays(1));
        Consent second = consents.createConsent("key-1", "client-a", "user-1", Set.of("accounts:read"), Duration.ofDays(1));
        log.info("[test] 判定: 重复提交 consentId 一致={}", first.getConsentId().equals(second.getConsentId()));
        assertSame(first, second, "相同 idempotencyKey 必须返回同一授权");
    }

    @Test
    void concurrentDuplicateCreateYieldsSingleConsent() throws InterruptedException {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Consent> results = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(consents.createConsent("key-race", "client-a", "user-1",
                            Set.of("accounts:read"), Duration.ofDays(1)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("[test] 判定: {} 并发提交产生 {} 个不同授权", threads, results.size());
        assertEquals(1, results.size(), "并发重复提交不得产生重复授权");
    }

    @Test
    void authorizeAndRevokeAreIdempotentAndPublishEventsOnce() {
        Consent consent = consents.createConsent("key-2", "client-a", "user-1", Set.of("accounts:read"), Duration.ofDays(1));
        consents.authorize(consent.getConsentId());
        consents.authorize(consent.getConsentId()); // 重复确认
        log.info("[test] 判定: 重复确认后状态={}", consents.find(consent.getConsentId()).orElseThrow().getStatus());
        assertEquals(ConsentStatus.AUTHORIZED, consents.find(consent.getConsentId()).orElseThrow().getStatus());

        consents.revoke(consent.getConsentId());
        consents.revoke(consent.getConsentId()); // 重复撤销
        Consent revoked = consents.find(consent.getConsentId()).orElseThrow();
        log.info("[test] 判定: 重复撤销后状态={}", revoked.getStatus());
        assertEquals(ConsentStatus.REVOKED, revoked.getStatus());

        var events = notifications.eventsFor(consent.getConsentId());
        long authorizedEvents = events.stream().filter(e -> e.getType() == ConsentEventType.CONSENT_AUTHORIZED).count();
        long revokedEvents = events.stream().filter(e -> e.getType() == ConsentEventType.CONSENT_REVOKED).count();
        log.info("[test] 判定: AUTHORIZED 事件数={} REVOKED 事件数={}（均应为 1）", authorizedEvents, revokedEvents);
        assertEquals(1, authorizedEvents, "重复确认不得产生重复通知");
        assertEquals(1, revokedEvents, "重复撤销不得产生重复通知");
    }

    @Test
    void expiredConsentTransitionsAndDoesNotRegress() {
        Consent consent = consents.createConsent("key-3", "client-a", "user-1", Set.of("accounts:read"), Duration.ofMinutes(5));
        consents.authorize(consent.getConsentId());
        clock.advance(Duration.ofMinutes(6));
        Consent expired = consents.find(consent.getConsentId()).orElseThrow();
        log.info("[test] 判定: 过期后状态={}", expired.getStatus());
        assertEquals(ConsentStatus.EXPIRED, expired.getStatus());

        // 过期后撤销不得回退状态
        Consent afterRevoke = consents.revoke(consent.getConsentId());
        log.info("[test] 判定: 过期后撤销 状态={}（应保持 EXPIRED）", afterRevoke.getStatus());
        assertEquals(ConsentStatus.EXPIRED, afterRevoke.getStatus());

        long expiredEvents = notifications.eventsFor(consent.getConsentId()).stream()
                .filter(e -> e.getType() == ConsentEventType.CONSENT_EXPIRED).count();
        assertEquals(1, expiredEvents, "过期事件只发布一次");
    }

    @Test
    void scopeChangeRequiresReconfirmation() {
        Consent consent = consents.createConsent("key-4", "client-a", "user-1", Set.of("accounts:read"), Duration.ofDays(1));
        consents.authorize(consent.getConsentId());

        Consent changed = consents.changeScopes(consent.getConsentId(),
                Set.of("accounts:read", "payments:write"), Duration.ofDays(1));
        log.info("[test] 判定: 范围变更后 status={} version={}", changed.getStatus(), changed.getVersion());
        assertEquals(ConsentStatus.PENDING_CONFIRMATION, changed.getStatus(), "范围变更必须重新确认");
        assertEquals(1, changed.getVersion());

        consents.authorize(consent.getConsentId());
        Consent reauthorized = consents.find(consent.getConsentId()).orElseThrow();
        log.info("[test] 判定: 重新确认后 status={} scopes={}", reauthorized.getStatus(), reauthorized.getScopes());
        assertEquals(ConsentStatus.AUTHORIZED, reauthorized.getStatus());
        assertEquals(Set.of("accounts:read", "payments:write"), reauthorized.getScopes());

        var events = notifications.eventsFor(consent.getConsentId());
        log.info("[test] 判定: 事件序列={}", events.stream().map(e -> e.getType() + "#" + e.getSequence()).toList());
        assertEquals(3, events.size());
        assertEquals(ConsentEventType.CONSENT_AUTHORIZED, events.get(0).getType());
        assertEquals(ConsentEventType.CONSENT_SCOPE_CHANGED, events.get(1).getType());
        assertEquals(ConsentEventType.CONSENT_AUTHORIZED, events.get(2).getType());
        // 序号单调递增
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).getSequence() > events.get(i - 1).getSequence(), "事件序号必须单调递增");
        }
    }

    @Test
    void backlogFullRejectsStateChangeAtomically() {
        // 每条授权积压上限 2：AUTHORIZED 之后还剩 1 个空位，制造一个额外未送达事件即可打满
        notifications.setBacklogCapacity(2);
        Consent consent = consents.createConsent("key-backlog", "client-a", "user-1",
                Set.of("accounts:read"), Duration.ofDays(1));
        consents.authorize(consent.getConsentId()); // 1 个未送达事件
        consents.changeScopes(consent.getConsentId(),
                Set.of("accounts:read", "payments:write"), Duration.ofDays(1)); // 第 2 个，积压满
        // 此时授权回到 PENDING_CONFIRMATION，重新确认需要再发一个事件 -> 必须被 BACKLOG_FULL 挡下
        NotificationRejectedException ex = assertThrows(NotificationRejectedException.class,
                () -> consents.authorize(consent.getConsentId()));
        log.info("[test] 判定: 积压满时 authorize 被拒 reason={}", ex.getReason());
        assertEquals(NotificationRejectedException.Reason.BACKLOG_FULL, ex.getReason());

        Consent unchanged = consents.find(consent.getConsentId()).orElseThrow();
        log.info("[test] 判定: 被挡后 status={} version={}（不得前移为 AUTHORIZED）",
                unchanged.getStatus(), unchanged.getVersion());
        assertEquals(ConsentStatus.PENDING_CONFIRMATION, unchanged.getStatus(), "被挡下时状态不得变化");
        assertEquals(1L, unchanged.getVersion());

        // 撤销同样被挡，状态不得变成 REVOKED
        NotificationRejectedException revokeEx = assertThrows(NotificationRejectedException.class,
                () -> consents.revoke(consent.getConsentId()));
        assertEquals(NotificationRejectedException.Reason.BACKLOG_FULL, revokeEx.getReason());
        assertEquals(ConsentStatus.PENDING_CONFIRMATION,
                consents.find(consent.getConsentId()).orElseThrow().getStatus());

        // 已接收事件顺序与状态不被弄乱
        var sequences = notifications.eventsFor(consent.getConsentId()).stream()
                .map(e -> e.getType().name() + "#" + e.getSequence()).toList();
        log.info("[test] 判定: 已接收事件={} pending={}", sequences, notifications.pendingCount(consent.getConsentId()));
        assertEquals(2, notifications.eventsFor(consent.getConsentId()).size());
    }
}
