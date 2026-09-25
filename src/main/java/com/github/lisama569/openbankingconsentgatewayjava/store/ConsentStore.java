package com.github.lisama569.openbankingconsentgatewayjava.store;

import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 授权本地存储。 */
@Component
public class ConsentStore {
    private final ConcurrentHashMap<String, Consent> consents = new ConcurrentHashMap<>();
    /** 幂等键（clientId + userId + idempotencyKey） -> consentId。 */
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();

    public Optional<Consent> findById(String consentId) {
        return Optional.ofNullable(consents.get(consentId));
    }

    public Optional<Consent> findByIdempotencyKey(String key) {
        String consentId = idempotencyIndex.get(key);
        return consentId == null ? Optional.empty() : findById(consentId);
    }

    /** 仅当幂等键不存在时写入，返回已有或新存入的授权。 */
    public Consent saveIfAbsent(String idempotencyKey, Consent consent) {
        String existing = idempotencyIndex.putIfAbsent(idempotencyKey, consent.getConsentId());
        if (existing != null) {
            return consents.get(existing);
        }
        consents.put(consent.getConsentId(), consent);
        return consent;
    }

    public Consent save(Consent consent) {
        consents.put(consent.getConsentId(), consent);
        return consent;
    }
}
