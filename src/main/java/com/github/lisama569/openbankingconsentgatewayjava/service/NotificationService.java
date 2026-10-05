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
    public enum DeliveryOutcome {
        DELIVERED,
        RETRY_SCHEDULED,
        FAILED,
        RATE_LIMITED,
        /** 同一授权下存在序号更小且尚未送达的事件，本次投递被顺序护栏挡下（未消耗配额、未计尝试）。 */
        BLOCKED_BY_ORDER
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
    /** 每条授权下“未送达事件”数量上限，超过后新事件以 BACKLOG_FULL 被拒绝。 */
    private volatile int backlogCapacity;

    @Autowired
    public NotificationService(RateLimiter rateLimiter,
                               @Value("${notification.max-attempts:3}") int maxAttempts,
                               @Value("${notification.backlog-capacity:100}") int backlogCapacity) {
        this(rateLimiter, Clock.systemUTC(), maxAttempts, backlogCapacity);
    }

    public NotificationService(RateLimiter rateLimiter, Clock clock, int maxAttempts) {
        this(rateLimiter, clock, maxAttempts, 100);
    }

    public NotificationService(RateLimiter rateLimiter, Clock clock, int maxAttempts, int backlogCapacity) {
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.backlogCapacity = backlogCapacity;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public void setBacklogCapacity(int backlogCapacity) {
        this.backlogCapacity = backlogCapacity;
    }

    public int getBacklogCapacity() {
        return backlogCapacity;
    }

    /**
     * 容量预检：若该授权再接收一个事件会超过积压上限，则按 BACKLOG_FULL 拒绝。
     * 供授权状态变更在修改状态前调用，保证状态变更与事件发布原子一致。
     * 注意：调用方需持有与 publish 相同的串行约束（此处为授权维度锁），
     * 预检与实际 publish 之间不会插入同一授权的其他 publish。
     */
    public void ensureCapacity(String consentId) {
        long pending = pendingCount(consentId);
        if (pending >= backlogCapacity) {
            log.info("[notify] ensureCapacity consentId={} -> BACKLOG_FULL（积压={} 容量={}）",
                    consentId, pending, backlogCapacity);
            throw new NotificationRejectedException(NotificationRejectedException.Reason.BACKLOG_FULL,
                    "backlog for consent " + consentId + " is full (" + pending + "/" + backlogCapacity + ")");
        }
    }

    /**
     * 记录事件（按 consentId 分配单调序号，按 consentId+type+version 幂等去重）。
     * 当该授权下未送达事件已达到 backlogCapacity 时，抛出 BACKLOG_FULL：
     * 不分配序号、不入列、不改变任何已接收事件的状态与顺序。
     */
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
            long pending = list.stream().filter(e -> e.getStatus() != DeliveryStatus.DELIVERED).count();
            if (pending >= backlogCapacity) {
                // 回收去重占位：本次未真正接收，相同事件未来重试时仍应允许
                dedupKeys.remove(dedupKey);
                log.info("[notify] publish consentId={} type={} 被拒：未送达积压={} 已达容量上限={} -> BACKLOG_FULL",
                        consentId, type, pending, backlogCapacity);
                throw new NotificationRejectedException(NotificationRejectedException.Reason.BACKLOG_FULL,
                        "backlog for consent " + consentId + " is full (" + pending + "/" + backlogCapacity + ")");
            }
            long sequence = sequences.computeIfAbsent(consentId, k -> new AtomicLong(0)).incrementAndGet();
            NotificationEvent event = new NotificationEvent("evt-" + UUID.randomUUID(), consentId, clientId,
                    type, sequence, consentVersion, Instant.now(clock));
            events.put(event.getEventId(), event);
            list.add(event);
            log.info("[notify] publish consentId={} type={} version={} seq={} eventId={} backlog={}/{}",
                    consentId, type, consentVersion, sequence, event.getEventId(), pending + 1, backlogCapacity);
            return event;
        }
    }

    /**
     * 尝试投递一个事件：
     * - 同授权下存在序号更小且未送达的事件 -> BLOCKED_BY_ORDER（不消耗限流配额、不计尝试、不改状态）；
     * - 限流 -> RATE_LIMITED；失败且可重试 -> RETRY_SCHEDULED；重试耗尽 -> FAILED；成功 -> DELIVERED。
     * 已送达事件重复/并发投递幂等返回 DELIVERED，不会重复送达。
     */
    public DeliveryOutcome deliver(String eventId) {
        NotificationEvent event = events.get(eventId);
        if (event == null) {
            throw new IllegalArgumentException("unknown event: " + eventId);
        }
        List<NotificationEvent> list = eventsByConsent.get(event.getConsentId());
        synchronized (list) {
            // 快速幂等：已送达无需再走顺序检查
            if (event.getStatus() == DeliveryStatus.DELIVERED) {
                log.info("[notify] deliver eventId={} 已 DELIVERED，幂等返回", eventId);
                return DeliveryOutcome.DELIVERED;
            }
            long blockerSeq = -1;
            for (NotificationEvent other : list) {
                if (other.getSequence() < event.getSequence() && other.getStatus() != DeliveryStatus.DELIVERED) {
                    blockerSeq = other.getSequence();
                    break;
                }
            }
            if (blockerSeq >= 0) {
                event.setLastError("BLOCKED_BY_ORDER: waiting for seq=" + blockerSeq);
                log.info("[notify] deliver eventId={} consentId={} seq={} -> BLOCKED_BY_ORDER（前序 seq={} 未送达）",
                        eventId, event.getConsentId(), event.getSequence(), blockerSeq);
                return DeliveryOutcome.BLOCKED_BY_ORDER;
            }
            // 固定锁序：先授权列表锁、后事件锁，避免与其他投递线程死锁
            synchronized (event) {
                return doDeliver(event);
            }
        }
    }

    /** 在已持有“授权列表锁 + 事件锁”的前提下执行实际投递。 */
    private DeliveryOutcome doDeliver(NotificationEvent event) {
        String eventId = event.getEventId();
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

    /** 该授权下尚未送达（PENDING/FAILED）的事件数，即当前积压深度。 */
    public long pendingCount(String consentId) {
        List<NotificationEvent> list = eventsByConsent.get(consentId);
        if (list == null) {
            return 0;
        }
        synchronized (list) {
            return list.stream().filter(e -> e.getStatus() != DeliveryStatus.DELIVERED).count();
        }
    }

    /**
     * 已连续送达的最大序号（送达水位）：从序号 1 开始连续 DELIVERED 的最大值；
     * 一个都未送达返回 0。水位之后的事件都会被顺序护栏挡住。
     */
    public long deliveredUpToSequence(String consentId) {
        List<NotificationEvent> list = eventsByConsent.get(consentId);
        if (list == null) {
            return 0;
        }
        synchronized (list) {
            long expected = 1;
            for (NotificationEvent e : list) {
                if (e.getSequence() == expected && e.getStatus() == DeliveryStatus.DELIVERED) {
                    expected++;
                } else {
                    break;
                }
            }
            return expected - 1;
        }
    }

    /**
     * 某事件当前被哪个前序序号挡住；没有被挡返回 -1。
     * 仅检查序号更小且尚未送达的第一个事件。
     */
    public long blockedBySequence(NotificationEvent event) {
        List<NotificationEvent> list = eventsByConsent.get(event.getConsentId());
        if (list == null || event.getStatus() == DeliveryStatus.DELIVERED) {
            return -1;
        }
        synchronized (list) {
            return list.stream()
                    .filter(o -> o.getSequence() < event.getSequence() && o.getStatus() != DeliveryStatus.DELIVERED)
                    .mapToLong(NotificationEvent::getSequence)
                    .min().orElse(-1);
        }
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
