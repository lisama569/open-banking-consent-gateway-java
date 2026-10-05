package com.github.lisama569.openbankingconsentgatewayjava.api;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.service.ConsentTokenService;
import com.github.lisama569.openbankingconsentgatewayjava.service.PartnerService;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenRejectionReason;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenService;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenValidationResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 令牌签发与校验入口，拒绝原因可区分。 */
@RestController
@RequestMapping("/api/tokens")
public class TokenController {
    private final TokenService tokenService;
    private final PartnerService partnerService;
    private final ConsentTokenService consentTokenService;

    public TokenController(TokenService tokenService, PartnerService partnerService,
                           ConsentTokenService consentTokenService) {
        this.tokenService = tokenService;
        this.partnerService = partnerService;
        this.consentTokenService = consentTokenService;
    }

    /** 客户端认证并签发令牌。认证失败返回 401 + 可区分原因。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> issue(@RequestBody Map<String, Object> body) {
        String clientId = (String) body.get("clientId");
        String secret = (String) body.get("secret");
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) body.getOrDefault("scopes", List.of());
        long ttlSeconds = ((Number) body.getOrDefault("ttlSeconds", 3600)).longValue();

        TokenRejectionReason authFailure = partnerService.authenticate(clientId, secret);
        if (authFailure != null) {
            return ResponseEntity.status(401).body(Map.of("error", authFailure.name()));
        }
        AccessToken token = tokenService.issue(clientId, Set.copyOf(scopes), Duration.ofSeconds(ttlSeconds));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tokenId", token.getTokenId());
        resp.put("clientId", token.getClientId());
        resp.put("scopes", token.getScopes());
        resp.put("expiresAt", token.getExpiresAt().toString());
        return ResponseEntity.ok(resp);
    }

    /**
     * 针对一条已确认授权换取访问凭证（授权绑定凭证）。
     * 凭证范围不超过授权范围；授权撤销/过期/范围变更后凭证立即失效。
     * 同一授权同一版本重复换取返回同一凭证。
     */
    @PostMapping("/consent-exchange")
    public ResponseEntity<Map<String, Object>> exchangeForConsent(@RequestBody Map<String, Object> body) {
        String consentId = (String) body.get("consentId");
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) body.getOrDefault("scopes", List.of());
        long ttlSeconds = ((Number) body.getOrDefault("ttlSeconds", 3600)).longValue();
        ConsentTokenService.ExchangeResult result;
        try {
            result = consentTokenService.exchange(consentId, Set.copyOf(scopes), Duration.ofSeconds(ttlSeconds));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "UNKNOWN_CONSENT", "consentId", String.valueOf(consentId)));
        }
        if (!result.ok()) {
            return ResponseEntity.status(409).body(Map.of("error", result.reason().name(), "consentId", consentId));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tokenId", result.token().getTokenId());
        resp.put("consentId", result.token().getConsentId());
        resp.put("consentVersion", result.token().getConsentVersion());
        resp.put("clientId", result.token().getClientId());
        resp.put("scopes", result.token().getScopes());
        resp.put("expiresAt", result.token().getExpiresAt().toString());
        return ResponseEntity.ok(resp);
    }

    /**
     * 校验令牌与所需范围，失败返回 403 + 可区分原因。
     * 授权绑定凭证自动走授权状态判定（CONSENT_REVOKED / CONSENT_EXPIRED / CONSENT_SCOPE_CHANGED），
     * 不绑定授权的旧令牌行为不变。
     */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        String tokenId = (String) body.get("tokenId");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) body.getOrDefault("requiredScopes", List.of());
        TokenValidationResult result = consentTokenService.isConsentToken(tokenId)
                ? consentTokenService.validate(tokenId, Set.copyOf(required))
                : tokenService.validate(tokenId, Set.copyOf(required));
        if (!result.valid()) {
            return ResponseEntity.status(403).body(Map.of("valid", false, "reason", result.reason().name()));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("valid", true);
        resp.put("clientId", result.token().getClientId());
        resp.put("scopes", result.token().getScopes());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/{tokenId}/revoke")
    public Map<String, Object> revoke(@PathVariable String tokenId) {
        tokenService.revoke(tokenId);
        return Map.of("tokenId", tokenId, "revoked", true);
    }
}
