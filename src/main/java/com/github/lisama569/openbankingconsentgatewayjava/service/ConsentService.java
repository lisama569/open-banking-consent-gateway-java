package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEventType;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户授权生命周期管理。
 *
 * 幂等语义：
 * - createConsent 以 idempotencyKey 去重，重复/并发提交返回同一授权记录；
 * - authorize/revoke 可重复调用，状态不回退，事件只发布一次；
 * - changeScopes 生成新版本并回到 PENDING_CONFIRMATION，需重新确认。
 */
@Service
public class ConsentService {
    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    private final Map<String, Consent> consents = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    /** 按 consentId 维度的稳定锁：授权状态变更与授权绑定凭证换取用同一把锁互斥。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final NotificationService notificationService;
    private final Clock clock;

    /** 取某条授权的稳定互斥锁（授权对象在范围变更时会被替换，不能锁授权实例本身）。 */
    public Object lockFor(String consentId) {
        return locks.computeIfAbsent(consentId, k -> new Object());
    }

    /** 不加锁地读取授权当前快照（调用方需已持有 {@link #lockFor}）。 */
    public Consent rawConsent(String consentId) {
        return consents.get(consentId);
    }

    @Autowired
    public ConsentService(NotificationService notificationService) {
        this(notificationService, Clock.systemUTC());
    }

    public ConsentService(NotificationService notificationService, Clock clock) {
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /** 创建授权请求（幂等：相同 idempotencyKey 返回相同结果，不重复创建、不重复通知）。 */
    public Consent createConsent(String idempotencyKey, String clientId, String userId,
                                 Set<String> scopes, Duration ttl) {
        String indexKey = clientId + "|" + idempotencyKey;
        String existingId = idempotencyIndex.get(indexKey);
        if (existingId != null) {
            Consent existing = consents.get(existingId);
            log.info("[consent] create 幂等命中 key={} -> consentId={}", indexKey, existingId);
            return existing;
        }
        // 以 key 为锁，保证并发下只创建一次
        synchronized (indexKey.intern()) {
            existingId = idempotencyIndex.get(indexKey);
            if (existingId != null) {
                log.info("[consent] create 并发幂等命中 key={} -> consentId={}", indexKey, existingId);
                return consents.get(existingId);
            }
            Instant now = Instant.now(clock);
            Consent consent = new Consent("consent-" + UUID.randomUUID(), clientId, userId,
                    scopes, now, now.plus(ttl));
            consents.put(consent.getConsentId(), consent);
            idempotencyIndex.put(indexKey, consent.getConsentId());
            log.info("[consent] create key={} consentId={} clientId={} userId={} scopes={} status=PENDING_CONFIRMATION",
                    indexKey, consent.getConsentId(), clientId, userId, scopes);
            return consent;
        }
    }

    /** 用户确认授权；重复确认幂等，已撤销/过期不回退。 */
    public Consent authorize(String consentId) {
        synchronized (lockFor(consentId)) {
            Consent consent = require(consentId);
            expireIfNeeded(consent);
            switch (consent.getStatus()) {
                case PENDING_CONFIRMATION -> {
                    notificationService.ensureCapacity(consentId);
                    consent.setStatus(ConsentStatus.AUTHORIZED);
                    log.info("[consent] authorize consentId={} version={} -> AUTHORIZED", consentId, consent.getVersion());
                    notificationService.publish(consentId, consent.getClientId(),
                            ConsentEventType.CONSENT_AUTHORIZED, consent.getVersion());
                }
                case AUTHORIZED -> log.info("[consent] authorize consentId={} 已 AUTHORIZED，幂等返回", consentId);
                default -> log.info("[consent] authorize consentId={} 当前状态 {}，拒绝状态回退", consentId, consent.getStatus());
            }
            return consents.get(consentId);
        }
    }

    /** 范围变更：生成新版本并回到待确认状态，需用户重新确认。 */
    public Consent changeScopes(String consentId, Set<String> newScopes, Duration ttl) {
        synchronized (lockFor(consentId)) {
            Consent consent = require(consentId);
            expireIfNeeded(consent);
            consent = consents.get(consentId);
            if (consent.getStatus() == ConsentStatus.REVOKED || consent.getStatus() == ConsentStatus.EXPIRED) {
                log.info("[consent] changeScopes consentId={} 状态 {} 不允许变更", consentId, consent.getStatus());
                return consent;
            }
            if (consent.getScopes().equals(newScopes)) {
                log.info("[consent] changeScopes consentId={} 范围未变化，幂等返回", consentId);
                return consent;
            }
            notificationService.ensureCapacity(consentId);
            Consent updated = new Consent(consent.getConsentId(), consent.getClientId(), consent.getUserId(),
                    newScopes, consent.getCreatedAt(), Instant.now(clock).plus(ttl));
            updated.setStatus(ConsentStatus.PENDING_CONFIRMATION);
            updated.setVersion(consent.getVersion() + 1);
            consents.put(consentId, updated);
            log.info("[consent] changeScopes consentId={} version {} -> {}, scopes={} -> {}, 需重新确认",
                    consentId, consent.getVersion(), updated.getVersion(), consent.getScopes(), newScopes);
            notificationService.publish(consentId, updated.getClientId(),
                    ConsentEventType.CONSENT_SCOPE_CHANGED, updated.getVersion());
            return updated;
        }
    }

    /** 撤销授权；重复撤销幂等，事件只发布一次。 */
    public Consent revoke(String consentId) {
        synchronized (lockFor(consentId)) {
            Consent consent = require(consentId);
            expireIfNeeded(consent);
            consent = consents.get(consentId);
            if (consent.getStatus() == ConsentStatus.REVOKED) {
                log.info("[consent] revoke consentId={} 已 REVOKED，幂等返回", consentId);
                return consent;
            }
            if (consent.getStatus() == ConsentStatus.EXPIRED) {
                log.info("[consent] revoke consentId={} 已 EXPIRED，不回退为 REVOKED", consentId);
                return consent;
            }
            notificationService.ensureCapacity(consentId);
            consent.setStatus(ConsentStatus.REVOKED);
            log.info("[consent] revoke consentId={} version={} -> REVOKED", consentId, consent.getVersion());
            notificationService.publish(consentId, consent.getClientId(),
                    ConsentEventType.CONSENT_REVOKED, consent.getVersion());
            return consent;
        }
    }

    public Optional<Consent> find(String consentId) {
        synchronized (lockFor(consentId)) {
            Consent consent = consents.get(consentId);
            if (consent != null) {
                expireIfNeeded(consent);
            }
            return Optional.ofNullable(consents.get(consentId));
        }
    }

    /** 惰性过期：到期且非终态时转为 EXPIRED，并发布一次过期事件。 */
    private void expireIfNeeded(Consent consent) {
        if (consent.getStatus() == ConsentStatus.REVOKED || consent.getStatus() == ConsentStatus.EXPIRED) {
            return;
        }
        if (!Instant.now(clock).isBefore(consent.getExpiresAt())) {
            // 到期是系统事实：状态先落终态，不受通知积压容量影响
            consent.setStatus(ConsentStatus.EXPIRED);
            log.info("[consent] expire consentId={} version={} -> EXPIRED", consent.getConsentId(), consent.getVersion());
            try {
                notificationService.publish(consent.getConsentId(), consent.getClientId(),
                        ConsentEventType.CONSENT_EXPIRED, consent.getVersion());
            } catch (NotificationRejectedException e) {
                // 积压已满：不回滚终态、不入队乱序，告警并保留原因，待积压释放后可人工/后续补发
                log.warn("[consent] expire consentId={} 过期事件因 {} 暂未入队，授权仍为 EXPIRED",
                        consent.getConsentId(), e.getReason());
            }
        }
    }

    private Consent require(String consentId) {
        Consent consent = consents.get(consentId);
        if (consent == null) {
            throw new IllegalArgumentException("unknown consent: " + consentId);
        }
        return consent;
    }
}
