package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.domain.AccessToken;
import com.github.lisama569.openbankingconsentgatewayjava.domain.Consent;
import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.NotificationEvent;
import com.github.lisama569.openbankingconsentgatewayjava.service.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 端到端一致性：接入 -> 令牌 -> 授权 -> 通知 -> 撤销后各入口状态一致。 */
class GatewayIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(GatewayIntegrationTest.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
    private final PartnerService partners = new PartnerService(clock);
    private final TokenService tokens = new TokenService(clock);
    private final NotificationService notifications = new NotificationService(new RateLimiter(clock, 100, 100), clock, 3);
    private final ConsentService consents = new ConsentService(notifications, clock);

    @Test
    void fullLifecycleKeepsStateConsistentAcrossEntries() {
        // 1. 合作伙伴接入
        partners.register("client-a", "Partner A");
        var cred = partners.issueInitialCredential("client-a");
        assertNull(partners.authenticate("client-a", cred.plainSecret()));
        log.info("[test] 步骤1: 客户端认证通过 clientId=client-a");

        // 2. 令牌签发
        AccessToken token = tokens.issue("client-a", Set.of("accounts:read"), Duration.ofHours(1));
        assertTrue(tokens.validate(token.getTokenId(), Set.of("accounts:read")).valid());
        log.info("[test] 步骤2: 令牌签发并校验通过 tokenId={}", token.getTokenId());

        // 3. 用户授权 + 事件投递
        Consent consent = consents.createConsent("idem-1", "client-a", "user-1", Set.of("accounts:read"), Duration.ofDays(30));
        consents.authorize(consent.getConsentId());
        List<NotificationEvent> events = notifications.eventsFor(consent.getConsentId());
        events.forEach(e -> notifications.deliver(e.getEventId()));
        List<NotificationEvent> delivered = notifications.deliveredTo("client-a");
        log.info("[test] 步骤3: 授权状态={} 已投递事件={}",
                consents.find(consent.getConsentId()).orElseThrow().getStatus(),
                delivered.stream().map(NotificationEvent::getType).toList());
        assertEquals(1, delivered.size());

        // 4. 撤销授权并吊销令牌：两个入口状态一致
        consents.revoke(consent.getConsentId());
        tokens.revoke(token.getTokenId());
        ConsentStatus consentStatus = consents.find(consent.getConsentId()).orElseThrow().getStatus();
        TokenValidationResult tokenResult = tokens.validate(token.getTokenId(), Set.of("accounts:read"));
        log.info("[test] 步骤4: 撤销后 consentStatus={} tokenReason={}", consentStatus, tokenResult.reason());
        assertEquals(ConsentStatus.REVOKED, consentStatus);
        assertFalse(tokenResult.valid());
        assertEquals(TokenRejectionReason.TOKEN_REVOKED, tokenResult.reason());

        // 5. 撤销事件补投后仍有序不重复
        notifications.eventsFor(consent.getConsentId()).forEach(e -> notifications.deliver(e.getEventId()));
        List<NotificationEvent> allDelivered = notifications.deliveredTo("client-a");
        log.info("[test] 步骤5: 最终收件箱序列={}",
                allDelivered.stream().map(e -> e.getType() + "#" + e.getSequence()).toList());
        assertEquals(2, allDelivered.size());
        assertEquals(List.of(1L, 2L), allDelivered.stream().map(NotificationEvent::getSequence).toList());
    }

    @Test
    void expiredTokenAndExpiredConsentAreConsistent() {
        AccessToken token = tokens.issue("client-a", Set.of("accounts:read"), Duration.ofMinutes(5));
        Consent consent = consents.createConsent("idem-2", "client-a", "user-1", Set.of("accounts:read"), Duration.ofMinutes(5));
        consents.authorize(consent.getConsentId());

        clock.advance(Duration.ofMinutes(6));
        TokenValidationResult tokenResult = tokens.validate(token.getTokenId(), Set.of("accounts:read"));
        ConsentStatus consentStatus = consents.find(consent.getConsentId()).orElseThrow().getStatus();
        log.info("[test] 判定: 过期后 tokenReason={} consentStatus={}", tokenResult.reason(), consentStatus);
        assertEquals(TokenRejectionReason.TOKEN_EXPIRED, tokenResult.reason());
        assertEquals(ConsentStatus.EXPIRED, consentStatus);
    }
}
