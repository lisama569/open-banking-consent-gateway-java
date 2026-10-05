package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEventType;
import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.NotificationEvent;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationRejectedException;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService.DeliveryOutcome;
import com.github.lisama569.openbankingconsentgatewayjava.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 授权内有序投递（乱序触发不越序）与每条授权的积压容量上限（边界、并发、原因可区分）。 */
class OrderedDeliveryAndBacklogTest {
    private static final Logger log = LoggerFactory.getLogger(OrderedDeliveryAndBacklogTest.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T00:00:00Z"));

    private NotificationService service(int backlog) {
        // 限流容量足够大，避免与顺序/积压语义混淆
        return new NotificationService(new RateLimiter(clock, 100_000, 100_000), clock, 3, backlog);
    }

    private NotificationEvent publishSeq(NotificationService s, String consentId, long version) {
        // 每个序号用不同事件类型/版本组合，避开幂等键
        ConsentEventType type = ConsentEventType.values()[(int) (version % ConsentEventType.values().length)];
        return s.publish(consentId, "client-a", type, version);
    }

    @Test
    void outOfOrderTriggersStillDeliverInSequence() {
        NotificationService s = service(100);
        NotificationEvent e1 = publishSeq(s, "c1", 1);
        NotificationEvent e2 = publishSeq(s, "c1", 2);
        NotificationEvent e3 = publishSeq(s, "c1", 3);
        log.info("[test] 输入: 乱序触发投递 seq=3,2，再投 seq=1");

        // 后两条即使先被触发，也不能越过前序送达
        DeliveryOutcome blocked3 = s.deliver(e3.getEventId());
        DeliveryOutcome blocked2 = s.deliver(e2.getEventId());
        log.info("[test] 判定: seq=3 -> {} blockedBy={}; seq=2 -> {} blockedBy={}",
                blocked3, s.blockedBySequence(e3), blocked2, s.blockedBySequence(e2));
        assertEquals(DeliveryOutcome.BLOCKED_BY_ORDER, blocked3);
        assertEquals(DeliveryOutcome.BLOCKED_BY_ORDER, blocked2);
        assertEquals(1L, s.blockedBySequence(e2));
        assertEquals(1L, s.blockedBySequence(e3), "挡住 seq=3 的应是最小的未送达前序 seq=1");
        assertEquals(0L, s.deliveredUpToSequence("c1"), "尚无事件送达，水位为 0");
        assertEquals(0, e3.getAttempts(), "被顺序挡住不得计入尝试次数");
        assertEquals(DeliveryStatus.PENDING, e3.getStatus());

        // 前序送达后，后序不再被挡（按触发再次投递）
        assertEquals(DeliveryOutcome.DELIVERED, s.deliver(e1.getEventId()));
        assertEquals(1L, s.deliveredUpToSequence("c1"));
        assertEquals(DeliveryOutcome.DELIVERED, s.deliver(e2.getEventId()));
        assertEquals(DeliveryOutcome.DELIVERED, s.deliver(e3.getEventId()));
        List<Long> inboxSeq = s.deliveredTo("client-a").stream().map(NotificationEvent::getSequence).toList();
        log.info("[test] 判定: 全部送达后收件箱序列={}", inboxSeq);
        assertEquals(List.of(1L, 2L, 3L), inboxSeq, "送达顺序必须严格等于授权内序号顺序");
        assertEquals(3L, s.deliveredUpToSequence("c1"));
    }

    @Test
    void repeatedAndConcurrentDeliverNeitherDuplicateNorReorder() throws Exception {
        NotificationService s = service(100);
        NotificationEvent e1 = publishSeq(s, "c1", 1);
        NotificationEvent e2 = publishSeq(s, "c1", 2);

        // 16 线程同时投递 seq=2：在 seq=1 未送达前一律 BLOCKED
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<DeliveryOutcome> outcomes2 = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    outcomes2.add(s.deliver(e2.getEventId()));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        log.info("[test] 判定: 并发投递 seq=2（前序未送达）结果集合={}", outcomes2);
        assertEquals(Set.of(DeliveryOutcome.BLOCKED_BY_ORDER), outcomes2);

        // seq=1 送达后，再对 seq=2 重复/并发投递，只允许送达一次
        s.deliver(e1.getEventId());
        ExecutorService pool2 = Executors.newFixedThreadPool(threads);
        CountDownLatch start2 = new CountDownLatch(1);
        Set<DeliveryOutcome> outcomes2b = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            pool2.submit(() -> {
                try {
                    start2.await();
                    outcomes2b.add(s.deliver(e2.getEventId()));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start2.countDown();
        pool2.shutdown();
        assertTrue(pool2.awaitTermination(5, TimeUnit.SECONDS));
        List<NotificationEvent> inbox = s.deliveredTo("client-a");
        log.info("[test] 判定: seq=1 送达后并发投 seq=2 结果={} 收件箱={}",
                outcomes2b, inbox.stream().map(NotificationEvent::getSequence).toList());
        assertTrue(outcomes2b.contains(DeliveryOutcome.DELIVERED));
        assertEquals(2, inbox.size(), "重复/并发送达不得产生重复事件");
        assertEquals(List.of(1L, 2L), inbox.stream().map(NotificationEvent::getSequence).toList());
    }

    @Test
    void backlogCapacityBoundaryRejectsExactlyAtLimit() {
        NotificationService s = service(3);
        NotificationEvent first = s.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        publishSeq(s, "c1", 1);
        publishSeq(s, "c1", 2);
        log.info("[test] 输入: 容量=3，已接收 3 个未送达事件 pendingCount={}", s.pendingCount("c1"));
        assertEquals(3, s.pendingCount("c1"));

        // 第 4 个：独立原因 BACKLOG_FULL，区别于 RATE_LIMITED / FAILED
        NotificationRejectedException ex = assertThrows(NotificationRejectedException.class,
                () -> s.publish("c1", "client-a", ConsentEventType.CONSENT_REVOKED, 9));
        log.info("[test] 判定: 第4个事件被拒 reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(NotificationRejectedException.Reason.BACKLOG_FULL, ex.getReason());

        // 被拒事件不得占用序号、不得入列、不得改变顺序
        List<Long> seqs = s.eventsFor("c1").stream().map(NotificationEvent::getSequence).toList();
        log.info("[test] 判定: 被拒后已接收序列={}（仍为1,2,3，状态不变）", seqs);
        assertEquals(List.of(1L, 2L, 3L), seqs);
        assertEquals(3, s.pendingCount("c1"));

        // 送掉一个释放容量后，相同变更可再接收，且排在 seq=4
        assertEquals(DeliveryOutcome.DELIVERED, s.deliver(first.getEventId()));
        NotificationEvent recovered = s.publish("c1", "client-a", ConsentEventType.CONSENT_REVOKED, 9);
        log.info("[test] 判定: 释放容量后被挡事件可接收 eventId={} seq={}", recovered.getEventId(), recovered.getSequence());
        assertEquals(4L, recovered.getSequence());
    }

    @Test
    void backlogFullDoesNotCorruptAcceptedOrderOrState() {
        NotificationService s = service(2);
        NotificationEvent e1 = s.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        publishSeq(s, "c1", 1);
        assertThrows(NotificationRejectedException.class,
                () -> s.publish("c1", "client-a", ConsentEventType.CONSENT_REVOKED, 9));
        // 再拒一次：幂等占位已回收，行为仍稳定为 BACKLOG_FULL 而非错误地去重返回
        assertThrows(NotificationRejectedException.class,
                () -> s.publish("c1", "client-a", ConsentEventType.CONSENT_REVOKED, 9));

        // 已接收事件在满容量期间仍可正常按序投递，积压随之下降
        assertEquals(DeliveryOutcome.DELIVERED, s.deliver(e1.getEventId()));
        assertEquals(DeliveryStatus.DELIVERED, e1.getStatus());
        log.info("[test] 判定: 满容量拒绝不影响已接收事件投递，水位={}", s.deliveredUpToSequence("c1"));
        assertEquals(1L, s.deliveredUpToSequence("c1"));
    }

    @Test
    void multipleConsentsConcurrentDeliveryStayOrderedAndIsolated() throws Exception {
        NotificationService s = service(100);
        int consentCount = 8;
        int perConsent = 6;
        // 每条授权发布 perConsent 个事件
        List<NotificationEvent> all = new ArrayList<>();
        for (int c = 0; c < consentCount; c++) {
            String consentId = "consent-" + c;
            for (int v = 1; v <= perConsent; v++) {
                all.add(s.publish(consentId, "client-" + c, ConsentEventType.values()[v % ConsentEventType.values().length], v));
            }
        }
        // 乱序：把所有事件打乱后并发投递；每条授权内部应最终严格 1..perConsent，不重复
        List<NotificationEvent> shuffled = new ArrayList<>(all);
        java.util.Collections.shuffle(shuffled, new java.util.Random(42));
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (NotificationEvent e : shuffled) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 可能多次被顺序挡住，循环投递直到送达（模拟事件源持续重试）
                    DeliveryOutcome outcome;
                    do {
                        outcome = s.deliver(e.getEventId());
                    } while (outcome == DeliveryOutcome.BLOCKED_BY_ORDER);
                    assertEquals(DeliveryOutcome.DELIVERED, outcome);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        for (int c = 0; c < consentCount; c++) {
            String consentId = "consent-" + c;
            List<Long> inboxSeq = s.deliveredTo("client-" + c).stream()
                    .map(NotificationEvent::getSequence).toList();
            List<Long> expected = new java.util.ArrayList<>();
            for (long v = 1; v <= perConsent; v++) {
                expected.add(v);
            }
            log.info("[test] 判定: {} 收件箱序列={} 水位={}", consentId, inboxSeq,
                    s.deliveredUpToSequence(consentId));
            assertEquals(expected, inboxSeq, consentId + " 必须有序且不重复");
            assertEquals(perConsent, inboxSeq.size());
            assertEquals(perConsent, s.deliveredUpToSequence(consentId));
        }
    }

    @Test
    void blockedByOrderIsDistinctFromRateLimitAndFailure() {
        // 顺序护栏在限流检查之前生效：即使配额耗尽，前序未送达仍报 BLOCKED_BY_ORDER
        NotificationService s = new NotificationService(new RateLimiter(clock, 0, 0), clock, 3, 100);
        NotificationEvent e1 = s.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        NotificationEvent e2 = publishSeq(s, "c1", 1);
        DeliveryOutcome outcome = s.deliver(e2.getEventId());
        log.info("[test] 判定: 限流+顺序同时存在 seq=2 -> {}（顺序优先，原因不混淆）", outcome);
        assertEquals(DeliveryOutcome.BLOCKED_BY_ORDER, outcome);
        assertEquals(0, e2.getAttempts());
    }
}
