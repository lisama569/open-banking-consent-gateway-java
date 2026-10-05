package com.github.lisama569.openbankingconsentgatewayjava.api;

import com.github.lisama569.openbankingconsentgatewayjava.domain.NotificationEvent;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 通知投递查询与触发入口（本地模拟）。 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 查询某授权的事件列表（按序号升序）。 */
    @GetMapping("/consents/{consentId}")
    public Map<String, Object> eventsFor(@PathVariable String consentId) {
        List<Map<String, Object>> events = notificationService.eventsFor(consentId).stream()
                .map(NotificationController::toView).toList();
        return Map.of("consentId", consentId, "events", events);
    }

    /** 查询某授权的投递进度：已送达到的序号、下一条可投递、因顺序被挡住的事件、积压占用。 */
    @GetMapping("/consents/{consentId}/progress")
    public Map<String, Object> progress(@PathVariable String consentId) {
        NotificationService.ConsentDeliveryProgress p = notificationService.deliveryProgress(consentId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("consentId", consentId);
        view.put("lastDeliveredSequence", p.lastDeliveredSequence());
        view.put("nextDeliverableEventId", p.nextDeliverableEventId());
        view.put("blockedEventIds", p.blockedEventIds());
        view.put("undeliveredCount", p.undeliveredCount());
        return view;
    }

    /** 触发一次投递尝试，结果区分 DELIVERED / RETRY_SCHEDULED / FAILED / RATE_LIMITED。 */
    @PostMapping("/{eventId}/deliver")
    public ResponseEntity<Map<String, Object>> deliver(@PathVariable String eventId) {
        try {
            NotificationService.DeliveryOutcome outcome = notificationService.deliver(eventId);
            return ResponseEntity.ok(Map.of("eventId", eventId, "outcome", outcome.name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "UNKNOWN_EVENT", "eventId", eventId));
        }
    }

    private static Map<String, Object> toView(NotificationEvent e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("eventId", e.getEventId());
        view.put("type", e.getType().name());
        view.put("sequence", e.getSequence());
        view.put("consentVersion", e.getConsentVersion());
        view.put("status", e.getStatus().name());
        view.put("attempts", e.getAttempts());
        return view;
    }
}
