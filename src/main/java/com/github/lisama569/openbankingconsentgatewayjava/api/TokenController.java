package com.github.lisama569.openbankingconsentgatewayjava.api;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
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

    public TokenController(TokenService tokenService, PartnerService partnerService) {
        this.tokenService = tokenService;
        this.partnerService = partnerService;
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

    /** 校验令牌与所需范围，失败返回 403 + 可区分原因。 */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        String tokenId = (String) body.get("tokenId");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) body.getOrDefault("requiredScopes", List.of());
        TokenValidationResult result = tokenService.validate(tokenId, Set.copyOf(required));
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
