package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEventType;
import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.NotificationEvent;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService.ConsentDeliveryProgress;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService.DeliveryOutcome;
import com.github.lisama569.openbankingconsentgatewayjava.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 有序投递：乱序触发不越序、进度可见、重复/并发触发不重复送达。 */
class NotificationOrderingTest {
    private static final Logger log = LoggerFactory.getLogger(NotificationOrderingTest.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));

    private NotificationService newService() {
        return new NotificationService(new RateLimiter(clock, 1000, 1000), clock, 3);
    }

    @Test
    void outOfOrderTriggersAreBlockedUntilPredecessorDelivered() {
        NotificationService service = newService();
        NotificationEvent e1 = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        NotificationEvent e2 = service.publish("c1", "client-a", ConsentEventType.CONSENT_SCOPE_CHANGED, 1);
        NotificationEvent e3 = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 1);
        log.info("[test] 输入: 3 个事件 seq=[{},{},{}]，按乱序 3->2->1->3->2->3 触发",
                e1.getSequence(), e2.getSequence(), e3.getSequence());

        // 乱序触发：后序事件被挡住
        assertEquals(DeliveryOutcome.BLOCKED_BY_ORDER, service.deliver(e3.getEventId()));
        assertEquals(DeliveryOutcome.BLOCKED_BY_ORDER, service.deliver(e2.getEventId()));
        log.info("[test] 判定: e3/e2 乱序触发均被 BLOCKED_BY_ORDER，status={}/{}",
                e2.getStatus(), e3.getStatus());
        assertEquals(DeliveryStatus.PENDING, e3.getStatus());

        // 进度可见：还没投递任何事件，e1 可投，e2/e3 被挡
        ConsentDeliveryProgress p0 = service.deliveryProgress("c1");
        log.info("[test] 判定: 初始进度 lastDeliveredSeq={} next={} blocked={}",
                p0.lastDeliveredSequence(), p0.nextDeliverableEventId(), p0.blockedEventIds());
        assertEquals(0, p0.lastDeliveredSequence());
        assertEquals(e1.getEventId(), p0.nextDeliverableEventId());
        assertEquals(List.of(e2.getEventId(), e3.getEventId()), p0.blockedEventIds());

        // 前序送达后，后序才可投递
        assertEquals(DeliveryOutcome.DELIVERED, service.deliver(e1.getEventId()));
        assertEquals(DeliveryOutcome.BLOCKED_BY_ORDER, service.deliver(e3.getEventId()),
                "e2 未送达时 e3 仍不得越过");
        ConsentDeliveryProgress p1 = service.deliveryProgress("c1");
        log.info("[test] 判定: e1 送达后进度 lastDeliveredSeq={} next={} blocked={}",
                p1.lastDeliveredSequence(), p1.nextDeliverableEventId(), p1.blockedEventIds());
        assertEquals(1, p1.lastDeliveredSequence());
        assertEquals(e2.getEventId(), p1.nextDeliverableEventId());
        assertEquals(List.of(e3.getEventId()), p1.blockedEventIds());

        assertEquals(DeliveryOutcome.DELIVERED, service.deliver(e2.getEventId()));
        assertEquals(DeliveryOutcome.DELIVERED, service.deliver(e3.getEventId()));

        List<Long> deliveredSeqs = service.deliveredTo("client-a").stream()
                .map(NotificationEvent::getSequence).toList();
        log.info("[test] 判定: 收件箱序列={}（必须按序）", deliveredSeqs);
        assertEquals(List.of(1L, 2L, 3L), deliveredSeqs);
    }

    @Test
    void blockedDeliveryConsumesNeitherAttemptsNorRateLimit() {
        // 限流容量 1、不补充：若被挡的投递消耗配额，后续 e1 投递会被限流
        NotificationService service = new NotificationService(new RateLimiter(clock, 1, 0), clock, 3);
        NotificationEvent e1 = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        NotificationEvent e2 = service.publish("c1", "client-a", ConsentEventType.CONSENT_SCOPE_CHANGED, 1);

        assertEquals(DeliveryOutcome.BLOCKED_BY_ORDER, service.deliver(e2.getEventId()));
        log.info("[test] 判定: 被挡后 e2 attempts={} status={}（不得消耗重试次数）", e2.getAttempts(), e2.getStatus());
        assertEquals(0, e2.getAttempts());
        assertEquals(DeliveryStatus.PENDING, e2.getStatus());

        DeliveryOutcome first = service.deliver(e1.getEventId());
        log.info("[test] 判定: e1 投递 outcome={}（被挡的 e2 不得消耗限流配额）", first);
        assertEquals(DeliveryOutcome.DELIVERED, first);
    }

    @Test
    void failedPredecessorStillBlocksUntilRedelivered() {
        NotificationService service = newService();
        service.setFailing("client-a", true);
        NotificationEvent e1 = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        NotificationEvent e2 = service.publish("c1", "client-a", ConsentEventType.CONSENT_SCOPE_CHANGED, 1);

        assertEquals(DeliveryOutcome.FAILED, retryToFail(service, e1));
        log.info("[test] 判定: e1 重试耗尽 status={}", e1.getStatus());
        assertEquals(DeliveryStatus.FAILED, e1.getStatus());

        // FAILED 的前序仍然挡住后序（严格按序，不跳过）
        assertEquals(DeliveryOutcome.BLOCKED_BY_ORDER, service.deliver(e2.getEventId()));
        log.info("[test] 判定: e1 FAILED 后 e2 仍被 BLOCKED_BY_ORDER");

        // 故障恢复，补投 e1 后 e2 放行
        service.setFailing("client-a", false);
        assertEquals(DeliveryOutcome.DELIVERED, service.deliver(e1.getEventId()));
        assertEquals(DeliveryOutcome.DELIVERED, service.deliver(e2.getEventId()));
        List<Long> seqs = service.deliveredTo("client-a").stream().map(NotificationEvent::getSequence).toList();
        log.info("[test] 判定: 补投后收件箱序列={}", seqs);
        assertEquals(List.of(1L, 2L), seqs);
    }

    private DeliveryOutcome retryToFail(NotificationService service, NotificationEvent e) {
        DeliveryOutcome outcome = null;
        for (int i = 0; i < 3; i++) {
            outcome = service.deliver(e.getEventId());
        }
        return outcome;
    }

    @Test
    void concurrentTriggersDeliverInOrderWithoutDuplicates() throws InterruptedException {
        NotificationService service = newService();
        int eventCount = 20;
        List<NotificationEvent> published = new java.util.ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            // 交替制造可去重的不同类型/版本组合
            published.add(service.publish("c1", "client-a", ConsentEventType.CONSENT_SCOPE_CHANGED, i));
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 每个线程都乱序重复触发整批事件，直到全部送达
                    boolean allDelivered = false;
                    while (!allDelivered) {
                        allDelivered = true;
                        for (int i = published.size() - 1; i >= 0; i--) {
                            if (service.deliver(published.get(i).getEventId()) != DeliveryOutcome.DELIVERED) {
                                allDelivered = false;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        List<NotificationEvent> delivered = service.deliveredTo("client-a");
        List<Long> seqs = delivered.stream().map(NotificationEvent::getSequence).toList();
        log.info("[test] 判定: {} 线程并发乱序触发后，收件箱大小={} 序列={}", threads, delivered.size(), seqs);
        assertEquals(eventCount, delivered.size(), "不得重复送达");
        List<Long> expected = new java.util.ArrayList<>();
        for (long i = 1; i <= eventCount; i++) {
            expected.add(i);
        }
        assertEquals(expected, seqs, "并发乱序触发下仍须按授权内顺序送达");
    }
}
