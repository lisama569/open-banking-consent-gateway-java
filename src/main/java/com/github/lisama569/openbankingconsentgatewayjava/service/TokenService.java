package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 访问令牌签发与校验，校验结果带可区分拒绝原因。 */
@Service
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final Map<String, AccessToken> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public TokenService() {
        this(Clock.systemUTC());
    }

    public TokenService(Clock clock) {
        this.clock = clock;
    }

    /** 认证成功后签发令牌。 */
    public AccessToken issue(String clientId, Set<String> scopes, Duration ttl) {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String tokenId = "tok-" + HexFormat.of().formatHex(buf);
        Instant now = Instant.now(clock);
        AccessToken token = new AccessToken(tokenId, clientId, scopes, now, now.plus(ttl));
        tokens.put(tokenId, token);
        log.info("[token] issue clientId={} scopes={} expiresAt={}", clientId, scopes, token.getExpiresAt());
        return token;
    }

    /** 校验令牌并检查所需范围，返回可区分结果。 */
    public TokenValidationResult validate(String tokenId, Set<String> requiredScopes) {
        AccessToken token = tokens.get(tokenId);
        if (token == null) {
            log.info("[token] validate tokenId={} -> TOKEN_NOT_FOUND", tokenId);
            return TokenValidationResult.reject(TokenRejectionReason.TOKEN_NOT_FOUND);
        }
        if (token.isRevoked()) {
            log.info("[token] validate tokenId={} -> TOKEN_REVOKED", tokenId);
            return TokenValidationResult.reject(TokenRejectionReason.TOKEN_REVOKED);
        }
        if (!Instant.now(clock).isBefore(token.getExpiresAt())) {
            log.info("[token] validate tokenId={} expiresAt={} -> TOKEN_EXPIRED", tokenId, token.getExpiresAt());
            return TokenValidationResult.reject(TokenRejectionReason.TOKEN_EXPIRED);
        }
        if (requiredScopes != null && !token.getScopes().containsAll(requiredScopes)) {
            log.info("[token] validate tokenId={} required={} actual={} -> SCOPE_NOT_GRANTED",
                    tokenId, requiredScopes, token.getScopes());
            return TokenValidationResult.reject(TokenRejectionReason.SCOPE_NOT_GRANTED);
        }
        log.info("[token] validate tokenId={} -> OK", tokenId);
        return TokenValidationResult.ok(token);
    }

    public void revoke(String tokenId) {
        AccessToken token = tokens.get(tokenId);
        if (token != null) {
            token.setRevoked(true);
            log.info("[token] revoke tokenId={}", tokenId);
        }
    }
}
