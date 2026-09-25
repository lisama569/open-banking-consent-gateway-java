package com.github.lisama569.openbankingconsentgatewayjava.web;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenService;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenValidationResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/** 令牌签发与校验入口。 */
@RestController
@RequestMapping("/api/tokens")
public class TokenController {
    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public record IssueRequest(String consentId, String clientId, Set<String> scopes) {}
    public record TokenView(String tokenId, String consentId, Set<String> scopes, String expiresAt) {}
    public record ValidateRequest(String tokenId, String clientId, Set<String> requiredScopes) {}

    @PostMapping
    public ResponseEntity<?> issue(@RequestBody IssueRequest request) {
        try {
            AccessToken token = tokenService.issue(request.consentId(), request.clientId(), request.scopes());
            return ResponseEntity.ok(new TokenView(token.getTokenId(), token.getConsentId(),
                    token.getScopes(), token.getExpiresAt().toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody ValidateRequest request) {
        TokenValidationResult result = tokenService.validate(request.tokenId(), request.clientId(),
                request.requiredScopes() == null ? Set.of() : request.requiredScopes());
        if (result.accepted()) {
            return ResponseEntity.ok(Map.of("valid", true, "tokenId", result.token().getTokenId()));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("valid", false, "reason", result.rejectionReason().name()));
    }
}
