package com.github.lisama569.openbankingconsentgatewayjava.api;

import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentBacklogFullException;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 用户授权生命周期入口。 */
@RestController
@RequestMapping("/api/consents")
public class ConsentController {
    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    /** 创建授权请求；Idempotency-Key 相同的重复提交返回同一记录。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String idempotencyKey = (String) body.get("idempotencyKey");
        String clientId = (String) body.get("clientId");
        String userId = (String) body.get("userId");
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) body.getOrDefault("scopes", List.of());
        long ttlSeconds = ((Number) body.getOrDefault("ttlSeconds", 86400)).longValue();
        Consent consent = consentService.createConsent(idempotencyKey, clientId, userId,
                Set.copyOf(scopes), Duration.ofSeconds(ttlSeconds));
        return toView(consent);
    }

    @PostMapping("/{consentId}/authorize")
    public ResponseEntity<Map<String, Object>> authorize(@PathVariable String consentId) {
        try {
            return ResponseEntity.ok(toView(consentService.authorize(consentId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "UNKNOWN_CONSENT", "consentId", consentId));
        }
    }

    /** 范围变更：生成新版本并回到待确认状态。 */
    @PostMapping("/{consentId}/scopes")
    public ResponseEntity<Map<String, Object>> changeScopes(@PathVariable String consentId,
                                                            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) body.getOrDefault("scopes", List.of());
        long ttlSeconds = ((Number) body.getOrDefault("ttlSeconds", 86400)).longValue();
        try {
            return ResponseEntity.ok(toView(consentService.changeScopes(consentId, Set.copyOf(scopes), Duration.ofSeconds(ttlSeconds))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "UNKNOWN_CONSENT", "consentId", consentId));
        }
    }

    @PostMapping("/{consentId}/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable String consentId) {
        try {
            return ResponseEntity.ok(toView(consentService.revoke(consentId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "UNKNOWN_CONSENT", "consentId", consentId));
        }
    }

    @GetMapping("/{consentId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String consentId) {
        return consentService.find(consentId)
                .map(c -> ResponseEntity.ok(toView(c)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "UNKNOWN_CONSENT", "consentId", consentId)));
    }

    /** 授权下通知积压达到容量上限：状态变更被挡下，返回独立可识别原因 BACKLOG_FULL。 */
    @ExceptionHandler(ConsentBacklogFullException.class)
    public ResponseEntity<Map<String, Object>> backlogFull(ConsentBacklogFullException e) {
        return ResponseEntity.status(429).body(Map.of(
                "error", "BACKLOG_FULL",
                "consentId", e.getConsentId(),
                "message", e.getMessage()));
    }

    private Map<String, Object> toView(Consent c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("consentId", c.getConsentId());
        view.put("clientId", c.getClientId());
        view.put("userId", c.getUserId());
        view.put("scopes", c.getScopes());
        view.put("status", c.getStatus().name());
        view.put("version", c.getVersion());
        view.put("expiresAt", c.getExpiresAt().toString());
        return view;
    }
}
