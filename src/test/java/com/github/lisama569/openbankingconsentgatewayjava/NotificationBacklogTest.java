package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEventType;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.NotificationEvent;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentBacklogFullException;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentService;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService.DeliveryOutcome;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService.PublishOutcome;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService.PublishResult;
import com.github.lisama569.openbankingconsentgatewayjava.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 积压容量上限：边界、独立原因、已接收事件顺序与状态不受损、多授权并发。 */
class NotificationBacklogTest {
    private static final Logger log = LoggerFactory.getLogger(NotificationBacklogTest.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));

    private NotificationService newService(int cap) {
        return new NotificationService(new RateLimiter(clock, 1000, 1000), clock, 3, cap);
    }

    @Test
    void capBoundaryRejectsWithDistinctReasonAndKeepsState() {
        NotificationService service = newService(3);
        NotificationEvent e1 = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        service.publish("c1", "client-a", ConsentEventType.CONSENT_SCOPE_CHANGED, 1);
        service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 1);
        log.info("[test] 输入: 容量=3，已发布 3 条未送达事件");

        // 第 4 条：tryPublish 返回 BACKLOG_FULL，publish 抛出独立异常
        PublishResult rejected = service.tryPublish("c1", "client-a", ConsentEventType.CONSENT_REVOKED, 1);
        log.info("[test] 判定: 第 4 条发布 outcome={}（区别于 FAILED/RATE_LIMITED）", rejected.outcome());
        assertEquals(PublishOutcome.BACKLOG_FULL, rejected.outcome());
        assertNull(rejected.event());
        assertThrows(ConsentBacklogFullException.class,
                () -> service.publish("c1", "client-a", ConsentEventType.CONSENT_REVOKED, 1),
                "publish 在积压满时必须抛出独立可识别异常");

        // 已接收事件的顺序与状态不受影响
        List<NotificationEvent> events = service.eventsFor("c1");
        log.info("[test] 判定: 已接收事件序列={} 状态={}",
                events.stream().map(NotificationEvent::getSequence).toList(),
                events.stream().map(NotificationEvent::getStatus).toList());
        assertEquals(List.of(1L, 2L, 3L), events.stream().map(NotificationEvent::getSequence).toList());

        // 送达一条后腾出容量，可继续发布且序号连续
        assertEquals(DeliveryOutcome.DELIVERED, service.deliver(e1.getEventId()));
        NotificationEvent e4 = service.publish("c1", "client-a", ConsentEventType.CONSENT_REVOKED, 1);
        log.info("[test] 判定: 腾出容量后新事件 seq={}", e4.getSequence());
        assertEquals(4L, e4.getSequence());
    }

    @Test
    void duplicatePublishIsStillIdempotentWhenFull() {
        NotificationService service = newService(1);
        NotificationEvent e1 = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        PublishResult dup = service.tryPublish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        log.info("[test] 判定: 积压满时重复发布 outcome={}（幂等去重优先于容量检查）", dup.outcome());
        assertEquals(PublishOutcome.DUPLICATE, dup.outcome());
        assertSame(e1, dup.event());
    }

    @Test
    void consentStateChangeIsBlockedWhenBacklogFull() {
        NotificationService notifications = newService(2);
        ConsentService consents = new ConsentService(notifications, clock);
        Consent consent = consents.createConsent("b1", "client-a", "user-1", Set.of("accounts:read"), Duration.ofDays(1));
        consents.authorize(consent.getConsentId());                                    // 事件 1（未送达）
        consents.changeScopes(consent.getConsentId(), Set.of("accounts:read", "payments:write"), Duration.ofDays(1)); // 事件 2（未送达）
        log.info("[test] 输入: 容量=2，授权下已有 2 条未送达事件，状态={}",
                consents.find(consent.getConsentId()).orElseThrow().getStatus());

        // 撤销被挡下：抛出独立原因，授权状态不回退、不部分变更
        ConsentBacklogFullException ex = assertThrows(ConsentBacklogFullException.class,
                () -> consents.revoke(consent.getConsentId()));
        Consent after = consents.find(consent.getConsentId()).orElseThrow();
        log.info("[test] 判定: 撤销被挡 exception={} 授权状态={} version={}（应保持 PENDING_CONFIRMATION/1）",
                ex.getMessage(), after.getStatus(), after.getVersion());
        assertEquals(ConsentStatus.PENDING_CONFIRMATION, after.getStatus());
        assertEquals(1, after.getVersion());
        assertEquals(2, notifications.eventsFor(consent.getConsentId()).size(), "被挡的变更不得产生事件");

        // 投递腾出席位后，同一变更可继续，且事件顺序正确
        notifications.eventsFor(consent.getConsentId()).forEach(e -> notifications.deliver(e.getEventId()));
        consents.revoke(consent.getConsentId());
        List<NotificationEvent> events = notifications.eventsFor(consent.getConsentId());
        log.info("[test] 判定: 腾出容量后撤销成功，事件序列={}",
                events.stream().map(e2 -> e2.getType() + "#" + e2.getSequence()).toList());
        assertEquals(ConsentStatus.REVOKED, consents.find(consent.getConsentId()).orElseThrow().getStatus());
        assertEquals(List.of(1L, 2L, 3L), events.stream().map(NotificationEvent::getSequence).toList());
        assertEquals(ConsentEventType.CONSENT_REVOKED, events.get(2).getType());
    }

    @Test
    void concurrentMultiConsentPublishAndDeliveryStaysConsistent() throws InterruptedException {
        int cap = 5;
        int consentCount = 4;
        int publishAttemptsPerConsent = 20;
        NotificationService service = newService(cap);
        List<String> consentIds = new java.util.ArrayList<>();
        for (int i = 0; i < consentCount; i++) {
            consentIds.add("cc-" + i);
        }
        Map<String, AtomicInteger> accepted = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> rejected = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        for (String consentId : consentIds) {
            String clientId = "client-" + consentId;
            for (int v = 0; v < publishAttemptsPerConsent; v++) {
                final int version = v;
                pool.submit(() -> {
                    try {
                        start.await();
                        PublishResult r = service.tryPublish(consentId, clientId,
                                ConsentEventType.CONSENT_SCOPE_CHANGED, version);
                        (r.outcome() == PublishOutcome.PUBLISHED ? accepted : rejected)
                                .computeIfAbsent(consentId, k -> new AtomicInteger()).incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        for (String consentId : consentIds) {
            int ok = accepted.getOrDefault(consentId, new AtomicInteger()).get();
            int full = rejected.getOrDefault(consentId, new AtomicInteger()).get();
            List<Long> seqs = service.eventsFor(consentId).stream().map(NotificationEvent::getSequence).toList();
            log.info("[test] 判定: consentId={} 接受={} 挡下={} 序号={}", consentId, ok, full, seqs);
            assertEquals(cap, ok, "并发下每条授权接受数必须恰好等于容量");
            assertEquals(publishAttemptsPerConsent - cap, full);
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), seqs, "并发下序号不得有缺口或重复");
        }

        // 并发乱序触发投递：每条授权收件箱仍按序、不重复
        ExecutorService deliverPool = Executors.newFixedThreadPool(8);
        CountDownLatch start2 = new CountDownLatch(1);
        for (String consentId : consentIds) {
            List<NotificationEvent> events = service.eventsFor(consentId);
            for (int t = 0; t < 2; t++) {
                pool_submit(deliverPool, start2, service, events);
            }
        }
        start2.countDown();
        deliverPool.shutdown();
        assertTrue(deliverPool.awaitTermination(30, TimeUnit.SECONDS));

        for (String consentId : consentIds) {
            List<Long> seqs = service.deliveredTo("client-" + consentId).stream()
                    .map(NotificationEvent::getSequence).toList();
            log.info("[test] 判定: consentId={} 收件箱序列={}", consentId, seqs);
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), seqs, "多授权并发投递不得乱序或重复");
        }
    }

    private void pool_submit(ExecutorService pool, CountDownLatch start,
                             NotificationService service, List<NotificationEvent> events) {
        pool.submit(() -> {
            try {
                start.await();
                boolean allDelivered = false;
                while (!allDelivered) {
                    allDelivered = true;
                    for (int i = events.size() - 1; i >= 0; i--) {
                        if (service.deliver(events.get(i).getEventId()) != DeliveryOutcome.DELIVERED) {
                            allDelivered = false;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
