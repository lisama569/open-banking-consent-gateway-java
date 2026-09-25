package com.github.lisama569.openbankingconsentgatewayjava.web;

import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public record GrantRequest(String clientId, String userId, Set<String> scopes,
                               String idempotencyKey, Long ttlSeconds) {}
    public record ScopeChangeRequest(Set<String> scopes, boolean confirmed) {}
    public record ConsentView(String consentId, String clientId, String userId, Set<String> scopes,
                              String status, long version, String expiresAt) {}

    @PostMapping
    public ResponseEntity<ConsentView> grant(@RequestBody GrantRequest request) {
        Consent consent = consentService.grant(request.clientId(), request.userId(),
                request.scopes(), request.idempotencyKey(), request.ttlSeconds());
        return ResponseEntity.ok(toView(consent));
    }

    @PostMapping("/{consentId}/scopes")
    public ResponseEntity<?> changeScopes(@PathVariable String consentId,
                                          @RequestBody ScopeChangeRequest request) {
        try {
            return ResponseEntity.ok(toView(consentService.changeScopes(consentId, request.scopes(), request.confirmed())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{consentId}/revoke")
    public ResponseEntity<?> revoke(@PathVariable String consentId) {
        try {
            return ResponseEntity.ok(toView(consentService.revoke(consentId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    private static ConsentView toView(Consent c) {
        return new ConsentView(c.getConsentId(), c.getClientId(), c.getUserId(), c.getScopes(),
                c.getStatus().name(), c.getVersion(), c.getExpiresAt().toString());
    }
}
