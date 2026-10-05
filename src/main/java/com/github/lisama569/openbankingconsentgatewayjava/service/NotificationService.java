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
import java.util.ArrayList;
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
 * - 事件按 consentId 维度分配单调递增序号，同一授权下严格按序号顺序投递：
 *   前一条未送达时，后一条即使被触发也返回 BLOCKED_BY_ORDER，不会越过它先送达；
 * - publish 以 (consentId, type, consentVersion) 幂等去重，不重复记录、不重复投递；
 * - 每条授权下未送达事件数量有容量上限（可配置），达到上限后新事件以 BACKLOG_FULL
 *   被挡下（独立原因，与投递失败、限流区分），已接收事件的顺序与状态不受影响；
 * - deliver 先查顺序，再过限流（RATE_LIMITED），失败计入重试，重试耗尽置 FAILED；
 * - 重试次数、限流配额、积压上限均可配置。
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 投递结果，用于区分失败类别。 */
    public enum DeliveryOutcome {
        DELIVERED, RETRY_SCHEDULED, FAILED, RATE_LIMITED,
        /** 前序事件尚未送达，本事件被顺序约束挡住（不计入重试、不消耗限流配额） */
        BLOCKED_BY_ORDER
    }

    /** 发布结果类别。 */
    public enum PublishOutcome { PUBLISHED, DUPLICATE, BACKLOG_FULL }

    /** 发布结果：BACKLOG_FULL 时 event 为 null。 */
    public record PublishResult(PublishOutcome outcome, NotificationEvent event) {
    }

    /**
     * 单条授权的投递进度快照：
     * lastDeliveredSequence 已送达到的序号；nextDeliverableEventId 下一条可投递事件；
     * blockedEventIds 因顺序未轮到而被挡住的事件；undeliveredCount 未送达总数（积压占用）。
     */
    public record ConsentDeliveryProgress(long lastDeliveredSequence,
                                          String nextDeliverableEventId,
                                          List<String> blockedEventIds,
                                          int undeliveredCount) {
    }

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
    private volatile int maxUndeliveredPerConsent;

    @Autowired
    public NotificationService(RateLimiter rateLimiter,
                               @Value("${notification.max-attempts:3}") int maxAttempts,
                               @Value("${notification.backlog.max-undelivered-per-consent:50}") int maxUndeliveredPerConsent) {
        this(rateLimiter, Clock.systemUTC(), maxAttempts, maxUndeliveredPerConsent);
    }

    public NotificationService(RateLimiter rateLimiter, Clock clock, int maxAttempts) {
        this(rateLimiter, clock, maxAttempts, 50);
    }

    public NotificationService(RateLimiter rateLimiter, Clock clock, int maxAttempts, int maxUndeliveredPerConsent) {
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.maxUndeliveredPerConsent = maxUndeliveredPerConsent;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public void setMaxUndeliveredPerConsent(int maxUndeliveredPerConsent) {
        this.maxUndeliveredPerConsent = maxUndeliveredPerConsent;
    }

    /**
     * 记录事件（按 consentId 分配单调序号，按 consentId+type+version 幂等去重）。
     * 积压达到上限时抛出 {@link ConsentBacklogFullException}。
     */
    public NotificationEvent publish(String consentId, String clientId, ConsentEventType type, long consentVersion) {
        PublishResult result = tryPublish(consentId, clientId, type, consentVersion);
        if (result.outcome() == PublishOutcome.BACKLOG_FULL) {
            throw new ConsentBacklogFullException(consentId, maxUndeliveredPerConsent);
        }
        return result.event();
    }

    /**
     * 记录事件（不抛异常版本）：返回 PUBLISHED / DUPLICATE / BACKLOG_FULL，
     * 调用方可据此区分积压上限与投递失败、限流。
     */
    public PublishResult tryPublish(String consentId, String clientId, ConsentEventType type, long consentVersion) {
        String dedupKey = consentId + "|" + type + "|" + consentVersion;
        List<NotificationEvent> list = eventsByConsent.computeIfAbsent(consentId, k -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            if (!dedupKeys.add(dedupKey)) {
                NotificationEvent existing = list.stream()
                        .filter(e -> e.getType() == type && e.getConsentVersion() == consentVersion)
                        .findFirst().orElseThrow();
                log.info("[notify] publish 幂等去重 consentId={} type={} version={} -> eventId={}",
                        consentId, type, consentVersion, existing.getEventId());
                return new PublishResult(PublishOutcome.DUPLICATE, existing);
            }
            long undelivered = list.stream().filter(e -> e.getStatus() != DeliveryStatus.DELIVERED).count();
            if (undelivered >= maxUndeliveredPerConsent) {
                dedupKeys.remove(dedupKey); // 未接收，不占用去重键，后续可重试
                log.info("[notify] publish consentId={} type={} version={} undelivered={}/{} -> BACKLOG_FULL（事件未接收，已有事件顺序与状态不变）",
                        consentId, type, consentVersion, undelivered, maxUndeliveredPerConsent);
                return new PublishResult(PublishOutcome.BACKLOG_FULL, null);
            }
            long sequence = sequences.computeIfAbsent(consentId, k -> new AtomicLong(0)).incrementAndGet();
            NotificationEvent event = new NotificationEvent("evt-" + UUID.randomUUID(), consentId, clientId,
                    type, sequence, consentVersion, Instant.now(clock));
            events.put(event.getEventId(), event);
            list.add(event);
            log.info("[notify] publish consentId={} type={} version={} seq={} eventId={} undelivered={}/{}",
                    consentId, type, consentVersion, sequence, event.getEventId(),
                    undelivered + 1, maxUndeliveredPerConsent);
            return new PublishResult(PublishOutcome.PUBLISHED, event);
        }
    }

    /**
     * 尝试投递一个事件：
     * 前序事件未送达 -> BLOCKED_BY_ORDER；限流 -> RATE_LIMITED；
     * 失败且可重试 -> RETRY_SCHEDULED；重试耗尽 -> FAILED。
     */
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
            NotificationEvent blocker = findUndeliveredPredecessor(event);
            if (blocker != null) {
                log.info("[notify] deliver eventId={} consentId={} seq={} 前序事件 seq={} 未送达 -> BLOCKED_BY_ORDER",
                        eventId, event.getConsentId(), event.getSequence(), blocker.getSequence());
                return DeliveryOutcome.BLOCKED_BY_ORDER;
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
            // 先入收件箱再置状态，保证并发下收件箱顺序与序号顺序一致
            mailboxes.computeIfAbsent(event.getClientId(), k -> new CopyOnWriteArrayList<>()).add(event);
            event.setStatus(DeliveryStatus.DELIVERED);
            event.setLastError(null);
            log.info("[notify] deliver eventId={} consentId={} seq={} attempt={} -> DELIVERED",
                    eventId, event.getConsentId(), event.getSequence(), event.getAttempts());
            return DeliveryOutcome.DELIVERED;
        }
    }

    /** 同一授权下序号更小且尚未送达的事件（含 FAILED，严格按序不跳过）。 */
    private NotificationEvent findUndeliveredPredecessor(NotificationEvent event) {
        List<NotificationEvent> list = eventsByConsent.get(event.getConsentId());
        if (list == null) {
            return null;
        }
        for (NotificationEvent e : list) {
            if (e.getSequence() >= event.getSequence()) {
                break;
            }
            if (e.getStatus() != DeliveryStatus.DELIVERED) {
                return e;
            }
        }
        return null;
    }

    /** 单条授权的投递进度：投递到哪一步、哪些事件因顺序未轮到被挡住。 */
    public ConsentDeliveryProgress deliveryProgress(String consentId) {
        List<NotificationEvent> ordered = eventsFor(consentId);
        long lastDelivered = 0;
        String nextDeliverable = null;
        List<String> blocked = new ArrayList<>();
        int undelivered = 0;
        for (NotificationEvent e : ordered) {
            if (e.getStatus() == DeliveryStatus.DELIVERED) {
                lastDelivered = Math.max(lastDelivered, e.getSequence());
            } else {
                undelivered++;
                if (nextDeliverable == null) {
                    nextDeliverable = e.getEventId();
                } else {
                    blocked.add(e.getEventId());
                }
            }
        }
        ConsentDeliveryProgress progress = new ConsentDeliveryProgress(
                lastDelivered, nextDeliverable, List.copyOf(blocked), undelivered);
        log.info("[notify] progress consentId={} lastDeliveredSeq={} next={} blocked={} undelivered={}",
                consentId, lastDelivered, nextDeliverable, blocked, undelivered);
        return progress;
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
