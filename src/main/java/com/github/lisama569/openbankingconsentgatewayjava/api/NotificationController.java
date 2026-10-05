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

    /** 查询某授权的事件列表（按序号升序）及当前投递水位/积压深度。 */
    @GetMapping("/consents/{consentId}")
    public Map<String, Object> eventsFor(@PathVariable String consentId) {
        List<Map<String, Object>> events = notificationService.eventsFor(consentId).stream()
                .map(e -> toView(e, notificationService.blockedBySequence(e))).toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("consentId", consentId);
        resp.put("deliveredUpToSequence", notificationService.deliveredUpToSequence(consentId));
        resp.put("pendingCount", notificationService.pendingCount(consentId));
        resp.put("backlogCapacity", notificationService.getBacklogCapacity());
        resp.put("events", events);
        return resp;
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

    private static Map<String, Object> toView(NotificationEvent e, long blockedBySequence) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("eventId", e.getEventId());
        view.put("type", e.getType().name());
        view.put("sequence", e.getSequence());
        view.put("consentVersion", e.getConsentVersion());
        view.put("status", e.getStatus().name());
        view.put("attempts", e.getAttempts());
        // -1 表示当前未被前序挡住；否则为挡住它的最小前序序号
        view.put("blockedBySequence", blockedBySequence);
        if (e.getLastError() != null) {
            view.put("lastError", e.getLastError());
        }
        return view;
    }
}
