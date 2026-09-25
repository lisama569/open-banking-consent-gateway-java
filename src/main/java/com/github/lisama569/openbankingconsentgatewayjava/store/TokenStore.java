package com.github.lisama569.openbankingconsentgatewayjava.store;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 令牌本地存储。 */
@Component
public class TokenStore {
    private final ConcurrentHashMap<String, AccessToken> tokens = new ConcurrentHashMap<>();

    public AccessToken save(AccessToken token) {
        tokens.put(token.getTokenId(), token);
        return token;
    }

    public Optional<AccessToken> findById(String tokenId) {
        return Optional.ofNullable(tokens.get(tokenId));
    }

    public List<AccessToken> findAll() {
        return List.copyOf(tokens.values());
    }
}
