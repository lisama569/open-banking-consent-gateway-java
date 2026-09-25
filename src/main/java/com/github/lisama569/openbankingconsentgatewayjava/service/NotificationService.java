package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEventType;
import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 授权事件的本地通知投递。
 *
 * 语义：
 * - 事件按 consentId 维度分配单调递增序号，保证有序；
 * - publish 以 (consentId, type, consentVersion) 幂等去重，不重复投递；
 * - deliver 先过限流（RATE_LIMITED，与投递失败区分），失败计入重试，重试耗尽置 FAILED；
 * - 重试次数与限流配额均可配置。
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 投递结果，用于区分失败类别。 */
    public enum DeliveryOutcome { DELIVERED, RETRY_SCHEDULED, FAILED, RATE_LIMITED }

    private final Map<String, NotificationEvent> events = new ConcurrentHashMap<>();
    private final Map<String, List<NotificationEvent>> eventsByConsent = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Set<String> dedupKeys = ConcurrentHashMap.newKeySet();
    /** 本地模拟的合作伙伴收件箱：clientId -> 已送达事件 */
    private final Map<String, List<NotificationEvent>> mailboxes = new ConcurrentHashMap<>();
    /** 本地模拟的投递故障开关（测试用） */
    private final Set<String> failingClients = ConcurrentHashMap.newKeySet();

    private final RateLimiter rateLimiter;
    private final Clock clock;
    private volatile int maxAttempts;

    @Autowired
    public NotificationService(RateLimiter rateLimiter,
                               @Value("${notification.max-attempts:3}") int maxAttempts) {
        this(rateLimiter, Clock.systemUTC(), maxAttempts);
    }

    public NotificationService(RateLimiter rateLimiter, Clock clock, int maxAttempts) {
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** 记录事件（按 consentId 分配单调序号，按 consentId+type+version 幂等去重）。 */
    public NotificationEvent publish(String consentId, String clientId, ConsentEventType type, long consentVersion) {
        String dedupKey = consentId + "|" + type + "|" + consentVersion;
        List<NotificationEvent> list = eventsByConsent.computeIfAbsent(consentId, k -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            if (!dedupKeys.add(dedupKey)) {
                NotificationEvent existing = list.stream()
                        .filter(e -> e.getType() == type && e.getConsentVersion() == consentVersion)
                        .findFirst().orElseThrow();
                log.info("[notify] publish 幂等去重 consentId={} type={} version={} -> eventId={}",
                        consentId, type, consentVersion, existing.getEventId());
                return existing;
            }
            long sequence = sequences.computeIfAbsent(consentId, k -> new AtomicLong(0)).incrementAndGet();
            NotificationEvent event = new NotificationEvent("evt-" + UUID.randomUUID(), consentId, clientId,
                    type, sequence, consentVersion, Instant.now(clock));
            events.put(event.getEventId(), event);
            list.add(event);
            log.info("[notify] publish consentId={} type={} version={} seq={} eventId={}",
                    consentId, type, consentVersion, sequence, event.getEventId());
            return event;
        }
    }

    /** 尝试投递一个事件：限流 -> RATE_LIMITED；失败且可重试 -> RETRY_SCHEDULED；重试耗尽 -> FAILED。 */
    public DeliveryOutcome deliver(String eventId) {
        NotificationEvent event = events.get(eventId);
        if (event == null) {
            throw new IllegalArgumentException("unknown event: " + eventId);
        }
        synchronized (event) {
            if (event.getStatus() == DeliveryStatus.DELIVERED) {
                log.info("[notify] deliver eventId={} 已 DELIVERED，幂等返回", eventId);
                return DeliveryOutcome.DELIVERED;
            }
            if (!rateLimiter.tryAcquire(event.getClientId())) {
                event.setLastError("RATE_LIMITED");
                log.info("[notify] deliver eventId={} clientId={} -> RATE_LIMITED（配额不足，不计入重试）",
                        eventId, event.getClientId());
                return DeliveryOutcome.RATE_LIMITED;
            }
            event.setAttempts(event.getAttempts() + 1);
            if (failingClients.contains(event.getClientId())) {
                event.setLastError("simulated delivery failure");
                if (event.getAttempts() >= maxAttempts) {
                    event.setStatus(DeliveryStatus.FAILED);
                    log.info("[notify] deliver eventId={} attempt={}/{} -> FAILED（重试耗尽）",
                            eventId, event.getAttempts(), maxAttempts);
                    return DeliveryOutcome.FAILED;
                }
                log.info("[notify] deliver eventId={} attempt={}/{} -> RETRY_SCHEDULED",
                        eventId, event.getAttempts(), maxAttempts);
                return DeliveryOutcome.RETRY_SCHEDULED;
            }
            event.setStatus(DeliveryStatus.DELIVERED);
            event.setLastError(null);
            mailboxes.computeIfAbsent(event.getClientId(), k -> new CopyOnWriteArrayList<>()).add(event);
            log.info("[notify] deliver eventId={} consentId={} seq={} attempt={} -> DELIVERED",
                    eventId, event.getConsentId(), event.getSequence(), event.getAttempts());
            return DeliveryOutcome.DELIVERED;
        }
    }

    /** 按 consentId 查询事件，按序号升序。 */
    public List<NotificationEvent> eventsFor(String consentId) {
        return eventsByConsent.getOrDefault(consentId, List.of()).stream()
                .sorted(Comparator.comparingLong(NotificationEvent::getSequence))
                .toList();
    }

    /** 已送达到某客户端收件箱的事件。 */
    public List<NotificationEvent> deliveredTo(String clientId) {
        return List.copyOf(mailboxes.getOrDefault(clientId, List.of()));
    }

    /** 测试辅助：模拟某客户端投递故障。 */
    public void setFailing(String clientId, boolean failing) {
        if (failing) {
            failingClients.add(clientId);
        } else {
            failingClients.remove(clientId);
        }
    }
}
