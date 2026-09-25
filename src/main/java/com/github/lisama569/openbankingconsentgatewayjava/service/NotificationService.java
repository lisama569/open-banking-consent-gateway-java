package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEvent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryRecord;
import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryStatus;
import com.github.lisama569.openbankingconsentgatewayjava.store.DeliveryStore;
import com.github.lisama569.openbankingconsentgatewayjava.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 授权事件本地通知投递。
 * - 同一 consentId 的事件按 sequence 串行投递，且每个事件只投递成功一次；
 * - 限流（RATE_LIMITED）与投递失败（FAILED）是可区分的状态；
 * - 重试次数与限流配额可配置。
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final EventStore eventStore;
    private final DeliveryStore deliveryStore;
    private final GatewayProperties properties;
    private final NotificationSink sink;
    private final Clock clock;
    /** 每个 consentId 一把锁，保证按序且不重复投递。 */
    private final ConcurrentHashMap<String, Object> consentLocks = new ConcurrentHashMap<>();
    /** 每个 clientId 的固定窗口限流计数。 */
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    @Autowired
    public NotificationService(EventStore eventStore, DeliveryStore deliveryStore,
                               GatewayProperties properties, NotificationSink sink) {
        this(eventStore, deliveryStore, properties, sink, Clock.systemUTC());
    }

    public NotificationService(EventStore eventStore, DeliveryStore deliveryStore,
                               GatewayProperties properties, NotificationSink sink, Clock clock) {
        this.eventStore = eventStore;
        this.deliveryStore = deliveryStore;
        this.properties = properties;
        this.sink = sink;
        this.clock = clock;
    }

    /** 投递事件（含同一授权下早于它的待投递事件），保证按序、不重复。 */
    public DeliveryRecord dispatch(ConsentEvent event) {
        Object lock = consentLocks.computeIfAbsent(event.getConsentId(), k -> new Object());
        synchronized (lock) {
            List<ConsentEvent> ordered = eventStore.findByConsentId(event.getConsentId()).stream()
                    .sorted((a, b) -> Long.compare(a.getSequence(), b.getSequence()))
                    .toList();
            // 先为所有事件建立投递记录，再按序投递。
            for (ConsentEvent e : ordered) {
                deliveryStore.saveIfAbsent(new DeliveryRecord(e.getEventId()));
            }
            for (ConsentEvent e : ordered) {
                DeliveryRecord record = deliveryStore.findByEventId(e.getEventId()).orElseThrow();
                if (record.getStatus() == DeliveryStatus.DELIVERED) {
                    continue; // 不重复投递
                }
                if (record.getStatus() == DeliveryStatus.PENDING) {
                    attempt(e, record);
                }
                if (record.getStatus() != DeliveryStatus.DELIVERED) {
                    break; // 前序事件未送达，后续事件等待，保证有序
                }
            }
            return deliveryStore.findByEventId(event.getEventId()).orElseThrow();
        }
    }

    /** 重试：仅对 FAILED / RATE_LIMITED 的记录有效，受最大重试次数约束。 */
    public DeliveryRecord retry(String eventId) {
        ConsentEvent event = eventStore.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("unknown event: " + eventId));
        DeliveryRecord record = deliveryStore.findByEventId(eventId)
                .orElseThrow(() -> new IllegalArgumentException("no delivery record for event: " + eventId));
        Object lock = consentLocks.computeIfAbsent(event.getConsentId(), k -> new Object());
        synchronized (lock) {
            if (record.getStatus() == DeliveryStatus.DELIVERED) {
                log.info("retry eventId={} result=no-op already-delivered", eventId);
                return record;
            }
            if (record.getAttempts() >= properties.getDeliveryMaxRetries()) {
                log.info("retry eventId={} attempts={} result=reject reason=max-retries-exceeded",
                        eventId, record.getAttempts());
                return record;
            }
            attempt(event, record);
            return record;
        }
    }

    private void attempt(ConsentEvent event, DeliveryRecord record) {
        if (!acquireQuota(event.getClientId())) {
            record.setStatus(DeliveryStatus.RATE_LIMITED);
            record.setLastError("rate limit exceeded for client " + event.getClientId());
            log.info("attempt eventId={} clientId={} result=rate-limited", event.getEventId(), event.getClientId());
            return;
        }
        record.setAttempts(record.getAttempts() + 1);
        record.setLastAttemptAt(clock.instant());
        try {
            sink.deliver(event);
            record.setStatus(DeliveryStatus.DELIVERED);
            record.setLastError(null);
            log.info("attempt eventId={} clientId={} attempts={} result=delivered",
                    event.getEventId(), event.getClientId(), record.getAttempts());
        } catch (NotificationSink.DeliveryException e) {
            record.setStatus(DeliveryStatus.FAILED);
            record.setLastError(e.getMessage());
            log.info("attempt eventId={} clientId={} attempts={} result=failed error={}",
                    event.getEventId(), event.getClientId(), record.getAttempts(), e.getMessage());
        }
    }

    private boolean acquireQuota(String clientId) {
        RateWindow window = rateWindows.computeIfAbsent(clientId, k -> new RateWindow());
        synchronized (window) {
            Instant now = clock.instant();
            if (now.isAfter(window.resetAt)) {
                window.count.set(0);
                window.resetAt = now.plusSeconds(properties.getDeliveryRateWindowSeconds());
            }
            if (window.count.get() >= properties.getDeliveryRateLimit()) {
                return false;
            }
            window.count.incrementAndGet();
            return true;
        }
    }

    private static final class RateWindow {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile Instant resetAt = Instant.MIN;
    }
}
