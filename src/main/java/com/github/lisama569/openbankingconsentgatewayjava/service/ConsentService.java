package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEvent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEventType;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import com.github.lisama569.openbankingconsentgatewayjava.store.ConsentStore;
import com.github.lisama569.openbankingconsentgatewayjava.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 用户授权生命周期。
 * 幂等语义：相同幂等键的重复/并发提交返回同一授权，不产生重复事件与通知；
 * 范围变更必须显式确认；撤销幂等且级联撤销令牌。
 */
@Service
public class ConsentService {
    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    private final ConsentStore consentStore;
    private final EventStore eventStore;
    private final TokenService tokenService;
    private final NotificationService notificationService;
    private final GatewayProperties properties;
    private final Clock clock;

    @Autowired
    public ConsentService(ConsentStore consentStore, EventStore eventStore, TokenService tokenService,
                          NotificationService notificationService, GatewayProperties properties) {
        this(consentStore, eventStore, tokenService, notificationService, properties, Clock.systemUTC());
    }

    public ConsentService(ConsentStore consentStore, EventStore eventStore, TokenService tokenService,
                          NotificationService notificationService, GatewayProperties properties, Clock clock) {
        this.consentStore = consentStore;
        this.eventStore = eventStore;
        this.tokenService = tokenService;
        this.notificationService = notificationService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 授予授权。相同 (clientId, userId, idempotencyKey) 的重复或并发提交
     * 返回同一授权，不重复产生事件与通知。
     */
    public Consent grant(String clientId, String userId, Set<String> scopes, String idempotencyKey, Long ttlSeconds) {
        String key = clientId + "|" + userId + "|" + idempotencyKey;
        Instant now = clock.instant();
        long ttl = ttlSeconds != null ? ttlSeconds : properties.getConsentTtlSeconds();
        Consent candidate = new Consent("con-" + UUID.randomUUID(), clientId, userId,
                Set.copyOf(scopes), ConsentStatus.ACTIVE, now, now.plusSeconds(ttl), 1);
        Consent stored = consentStore.saveIfAbsent(key, candidate);
        if (stored != candidate) {
            log.info("grant clientId={} userId={} idempotencyKey={} result=duplicate consentId={}",
                    clientId, userId, idempotencyKey, stored.getConsentId());
            return stored;
        }
        log.info("grant clientId={} userId={} idempotencyKey={} scopes={} result=created consentId={}",
                clientId, userId, idempotencyKey, scopes, stored.getConsentId());
        emit(stored, ConsentEventType.GRANTED);
        return stored;
    }

    /**
     * 范围变更：必须显式确认（confirmed=true）；范围相同则幂等无操作；
     * 变更后旧令牌撤销，需按新范围重新签发。
     */
    public synchronized Consent changeScopes(String consentId, Set<String> newScopes, boolean confirmed) {
        Consent consent = requireConsent(consentId);
        refreshExpiry(consent);
        if (consent.getStatus() != ConsentStatus.ACTIVE) {
            throw new IllegalStateException("consent not active: " + consent.getStatus());
        }
        if (!confirmed) {
            log.info("changeScopes consentId={} newScopes={} result=reject reason=confirmation-required", consentId, newScopes);
            throw new IllegalStateException("scope change requires explicit re-confirmation");
        }
        if (consent.getScopes().equals(newScopes)) {
            log.info("changeScopes consentId={} newScopes={} result=no-op", consentId, newScopes);
            return consent;
        }
        consent.setScopes(Set.copyOf(newScopes));
        consent.setVersion(consent.getVersion() + 1);
        tokenService.revokeByConsent(consentId);
        log.info("changeScopes consentId={} newScopes={} version={} result=changed",
                consentId, newScopes, consent.getVersion());
        emit(consent, ConsentEventType.SCOPE_CHANGED);
        return consent;
    }

    /** 撤销：幂等；已撤销或已过期授权不产生重复事件；级联撤销令牌。 */
    public synchronized Consent revoke(String consentId) {
        Consent consent = requireConsent(consentId);
        refreshExpiry(consent);
        if (consent.getStatus() == ConsentStatus.REVOKED) {
            log.info("revoke consentId={} result=no-op already-revoked", consentId);
            return consent;
        }
        if (consent.getStatus() == ConsentStatus.EXPIRED) {
            log.info("revoke consentId={} result=no-op already-expired", consentId);
            return consent;
        }
        consent.setStatus(ConsentStatus.REVOKED);
        tokenService.revokeByConsent(consentId);
        log.info("revoke consentId={} result=revoked", consentId);
        emit(consent, ConsentEventType.REVOKED);
        return consent;
    }

    /** 过期检查：到期授权转为 EXPIRED 并级联撤销令牌，只触发一次事件。 */
    public synchronized Consent expireIfDue(String consentId) {
        Consent consent = requireConsent(consentId);
        if (consent.getStatus() == ConsentStatus.ACTIVE
                && !clock.instant().isBefore(consent.getExpiresAt())) {
            consent.setStatus(ConsentStatus.EXPIRED);
            tokenService.revokeByConsent(consentId);
            log.info("expire consentId={} result=expired", consentId);
            emit(consent, ConsentEventType.EXPIRED);
        }
        return consent;
    }

    private Consent requireConsent(String consentId) {
        return consentStore.findById(consentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown consent: " + consentId));
    }

    private void refreshExpiry(Consent consent) {
        if (consent.getStatus() == ConsentStatus.ACTIVE
                && !clock.instant().isBefore(consent.getExpiresAt())) {
            expireIfDue(consent.getConsentId());
        }
    }

    private void emit(Consent consent, ConsentEventType type) {
        long seq = eventStore.nextSequence(consent.getConsentId());
        ConsentEvent event = new ConsentEvent("evt-" + UUID.randomUUID(), consent.getConsentId(),
                consent.getClientId(), type, seq, clock.instant());
        eventStore.save(event);
        log.info("emit consentId={} type={} sequence={} eventId={}",
                consent.getConsentId(), type, seq, event.getEventId());
        notificationService.dispatch(event);
    }
}
