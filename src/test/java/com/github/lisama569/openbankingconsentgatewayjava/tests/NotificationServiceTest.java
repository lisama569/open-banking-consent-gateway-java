package com.github.lisama569.openbankingconsentgatewayjava.tests;

import com.github.lisama569.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEvent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEventType;
import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryRecord;
import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryStatus;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationSink;
import com.github.lisama569.openbankingconsentgatewayjava.store.DeliveryStore;
import com.github.lisama569.openbankingconsentgatewayjava.store.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 通知投递：重试、限流与失败可区分、按授权有序且不重复。 */
class NotificationServiceTest {
    private static final Logger log = LoggerFactory.getLogger(NotificationServiceTest.class);

    private MutableClock clock;
    private EventStore eventStore;
    private DeliveryStore deliveryStore;
    private GatewayProperties properties;
    private ControllableSink sink;
    private NotificationService notificationService;

    /** 可控制失败次数的本地投递桩。 */
    static class ControllableSink implements NotificationSink {
        final List<ConsentEvent> delivered = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger(0);

        @Override
        public void deliver(ConsentEvent event) throws DeliveryException {
            if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new DeliveryException("simulated sink outage");
            }
            delivered.add(event);
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        eventStore = new EventStore();
        deliveryStore = new DeliveryStore();
        properties = new GatewayProperties();
        properties.setDeliveryRateLimit(100);
        properties.setDeliveryMaxRetries(3);
        sink = new ControllableSink();
        notificationService = new NotificationService(eventStore, deliveryStore, properties, sink, clock);
    }

    private ConsentEvent newEvent(String consentId, String clientId, ConsentEventType type) {
        long seq = eventStore.nextSequence(consentId);
        ConsentEvent event = new ConsentEvent("evt-" + consentId + "-" + seq, consentId, clientId,
                type, seq, clock.instant());
        return eventStore.save(event);
    }

    @Test
    void failedDeliveryIsRetriableAndEventuallyDelivered() {
        sink.failuresRemaining.set(2); // 前两次投递失败
        ConsentEvent event = newEvent("con-1", "client-1", ConsentEventType.GRANTED);

        DeliveryRecord first = notificationService.dispatch(event);
        log.info("case=retry dispatch#1 status={} attempts={} error={}", first.getStatus(), first.getAttempts(), first.getLastError());
        assertEquals(DeliveryStatus.FAILED, first.getStatus());

        DeliveryRecord second = notificationService.retry(event.getEventId());
        log.info("case=retry dispatch#2 status={} attempts={}", second.getStatus(), second.getAttempts());
        assertEquals(DeliveryStatus.FAILED, second.getStatus());

        DeliveryRecord third = notificationService.retry(event.getEventId());
        log.info("case=retry dispatch#3 status={} attempts={} delivered={}", third.getStatus(), third.getAttempts(), sink.delivered.size());
        assertEquals(DeliveryStatus.DELIVERED, third.getStatus());
        assertEquals(1, sink.delivered.size(), "同一事件只成功投递一次");

        DeliveryRecord fourth = notificationService.retry(event.getEventId());
        assertEquals(DeliveryStatus.DELIVERED, fourth.getStatus());
        assertEquals(1, sink.delivered.size(), "已送达事件重试为 no-op");
    }

    @Test
    void retryIsRejectedAfterMaxRetries() {
        sink.failuresRemaining.set(10);
        ConsentEvent event = newEvent("con-1", "client-1", ConsentEventType.GRANTED);
        notificationService.dispatch(event); // attempts=1
        notificationService.retry(event.getEventId()); // attempts=2
        notificationService.retry(event.getEventId()); // attempts=3
        DeliveryRecord record = notificationService.retry(event.getEventId()); // 超过 maxRetries=3
        log.info("case=max-retries status={} attempts={}", record.getStatus(), record.getAttempts());
        assertEquals(DeliveryStatus.FAILED, record.getStatus());
        assertEquals(3, record.getAttempts(), "超过最大重试次数后不再尝试");
    }

    @Test
    void rateLimitIsDistinguishableFromFailure() {
        properties.setDeliveryRateLimit(2);
        properties.setDeliveryRateWindowSeconds(60);
        ConsentEvent e1 = newEvent("con-1", "client-1", ConsentEventType.GRANTED);
        ConsentEvent e2 = newEvent("con-2", "client-1", ConsentEventType.GRANTED);
        ConsentEvent e3 = newEvent("con-3", "client-1", ConsentEventType.GRANTED);

        DeliveryRecord r1 = notificationService.dispatch(e1);
        DeliveryRecord r2 = notificationService.dispatch(e2);
        DeliveryRecord r3 = notificationService.dispatch(e3);
        log.info("case=rate-limit r1={} r2={} r3={} error={}", r1.getStatus(), r2.getStatus(), r3.getStatus(), r3.getLastError());
        assertEquals(DeliveryStatus.DELIVERED, r1.getStatus());
        assertEquals(DeliveryStatus.DELIVERED, r2.getStatus());
        assertEquals(DeliveryStatus.RATE_LIMITED, r3.getStatus(), "限流须与投递失败区分");
        assertEquals(0, r3.getAttempts(), "限流不消耗投递尝试次数");

        clock.advanceSeconds(61); // 窗口过后配额恢复
        DeliveryRecord retried = notificationService.retry(e3.getEventId());
        log.info("case=rate-limit-recovery status={} attempts={}", retried.getStatus(), retried.getAttempts());
        assertEquals(DeliveryStatus.DELIVERED, retried.getStatus());
    }

    @Test
    void eventsForSameConsentAreDeliveredInOrderWithoutDuplicates() {
        sink.failuresRemaining.set(1); // 第一个事件首次投递失败
        ConsentEvent e1 = newEvent("con-1", "client-1", ConsentEventType.GRANTED);
        ConsentEvent e2 = newEvent("con-1", "client-1", ConsentEventType.SCOPE_CHANGED);
        ConsentEvent e3 = newEvent("con-1", "client-1", ConsentEventType.REVOKED);

        notificationService.dispatch(e1); // e1 失败
        DeliveryRecord blocked = notificationService.dispatch(e3); // e1 未送达，e2/e3 须等待
        log.info("case=ordering blocked-status={} delivered={}", blocked.getStatus(), sink.delivered.size());
        assertNotEquals(DeliveryStatus.DELIVERED, blocked.getStatus());
        assertEquals(0, sink.delivered.size(), "前序事件未送达时后续事件不得投递");

        notificationService.retry(e1.getEventId()); // e1 恢复
        notificationService.dispatch(e3); // 依次补投 e2、e3
        List<String> deliveredIds = sink.delivered.stream().map(ConsentEvent::getEventId).toList();
        log.info("case=ordering delivered={}", deliveredIds);
        assertEquals(List.of(e1.getEventId(), e2.getEventId(), e3.getEventId()), deliveredIds,
                "同一授权的事件须按序且不重复送达");
    }
}
