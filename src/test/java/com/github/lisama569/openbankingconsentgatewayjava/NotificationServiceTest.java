package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEventType;
import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.NotificationEvent;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService.DeliveryOutcome;
import com.github.lisama569.openbankingconsentgatewayjava.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 通知投递：按授权维度有序不重复，重试与限流可配置且可区分。 */
class NotificationServiceTest {
    private static final Logger log = LoggerFactory.getLogger(NotificationServiceTest.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));

    @Test
    void retryExhaustionLeadsToFailed() {
        NotificationService service = new NotificationService(new RateLimiter(clock, 100, 100), clock, 3);
        service.setFailing("client-a", true);
        NotificationEvent event = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        log.info("[test] 输入: eventId={} maxAttempts=3 模拟故障=开", event.getEventId());

        assertEquals(DeliveryOutcome.RETRY_SCHEDULED, service.deliver(event.getEventId()));
        assertEquals(DeliveryOutcome.RETRY_SCHEDULED, service.deliver(event.getEventId()));
        DeliveryOutcome third = service.deliver(event.getEventId());
        log.info("[test] 判定: 第3次投递 outcome={} status={} attempts={}",
                third, event.getStatus(), event.getAttempts());
        assertEquals(DeliveryOutcome.FAILED, third);
        assertEquals(DeliveryStatus.FAILED, event.getStatus());
        assertEquals(3, event.getAttempts());

        // 故障恢复后重试仍可成功（FAILED 后允许人工重投）
        service.setFailing("client-a", false);
        DeliveryOutcome recovered = service.deliver(event.getEventId());
        log.info("[test] 判定: 故障恢复后重投 outcome={}", recovered);
        assertEquals(DeliveryOutcome.DELIVERED, recovered);
    }

    @Test
    void rateLimitIsDistinguishableFromFailure() {
        // 容量 2，不补充：第 3 次投递必被限流
        NotificationService service = new NotificationService(new RateLimiter(clock, 2, 0), clock, 3);
        NotificationEvent e1 = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        NotificationEvent e2 = service.publish("c2", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        NotificationEvent e3 = service.publish("c3", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);

        assertEquals(DeliveryOutcome.DELIVERED, service.deliver(e1.getEventId()));
        assertEquals(DeliveryOutcome.DELIVERED, service.deliver(e2.getEventId()));
        DeliveryOutcome limited = service.deliver(e3.getEventId());
        log.info("[test] 判定: 配额耗尽后投递 outcome={} attempts={}（限流不计入重试）", limited, e3.getAttempts());
        assertEquals(DeliveryOutcome.RATE_LIMITED, limited);
        assertEquals(0, e3.getAttempts(), "限流不得计入投递尝试次数");
        assertEquals(DeliveryStatus.PENDING, e3.getStatus(), "限流不得标记为失败");

        // 限流与投递失败是不同类别
        assertNotEquals(DeliveryOutcome.FAILED, limited);
    }

    @Test
    void eventsAreOrderedAndNotDuplicatedPerConsent() {
        NotificationService service = new NotificationService(new RateLimiter(clock, 100, 100), clock, 3);
        NotificationEvent a1 = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0);
        NotificationEvent a2 = service.publish("c1", "client-a", ConsentEventType.CONSENT_SCOPE_CHANGED, 1);
        NotificationEvent dup = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 0); // 重复发布
        NotificationEvent a3 = service.publish("c1", "client-a", ConsentEventType.CONSENT_AUTHORIZED, 1);

        log.info("[test] 判定: 重复发布去重={}", dup == a1);
        assertSame(a1, dup, "相同 (consentId,type,version) 不得重复记录");

        List<NotificationEvent> events = service.eventsFor("c1");
        log.info("[test] 判定: 事件序列={}", events.stream().map(e -> e.getType() + "#" + e.getSequence()).toList());
        assertEquals(List.of(a1, a2, a3), events, "事件须按序号有序");

        // 投递后收件箱不重复
        service.deliver(a1.getEventId());
        service.deliver(a1.getEventId()); // 重复投递同一事件
        service.deliver(a2.getEventId());
        service.deliver(a3.getEventId());
        List<NotificationEvent> delivered = service.deliveredTo("client-a");
        log.info("[test] 判定: 收件箱事件数={} 序列={}",
                delivered.size(), delivered.stream().map(NotificationEvent::getSequence).toList());
        assertEquals(3, delivered.size(), "重复投递不得产生重复送达");
        assertEquals(List.of(1L, 2L, 3L), delivered.stream().map(NotificationEvent::getSequence).toList());
    }
}
