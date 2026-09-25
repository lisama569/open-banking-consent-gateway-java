package com.github.lisama569.openbankingconsentgatewayjava.web;

import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryRecord;
import com.github.lisama569.openbankingconsentgatewayjava.service.NotificationService;
import com.github.lisama569.openbankingconsentgatewayjava.store.DeliveryStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 通知投递查询与重试入口。 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notificationService;
    private final DeliveryStore deliveryStore;

    public NotificationController(NotificationService notificationService, DeliveryStore deliveryStore) {
        this.notificationService = notificationService;
        this.deliveryStore = deliveryStore;
    }

    public record DeliveryView(String eventId, String status, int attempts, String lastError) {}

    @GetMapping("/{eventId}")
    public ResponseEntity<?> get(@PathVariable String eventId) {
        return deliveryStore.findByEventId(eventId)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(toView(r)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "no delivery record for event: " + eventId)));
    }

    @PostMapping("/{eventId}/retry")
    public ResponseEntity<?> retry(@PathVariable String eventId) {
        try {
            return ResponseEntity.ok(toView(notificationService.retry(eventId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    private static DeliveryView toView(DeliveryRecord r) {
        return new DeliveryView(r.getEventId(), r.getStatus().name(), r.getAttempts(), r.getLastError());
    }
}
