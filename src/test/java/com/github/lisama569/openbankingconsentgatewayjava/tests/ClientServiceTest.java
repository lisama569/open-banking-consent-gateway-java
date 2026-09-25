package com.github.lisama569.openbankingconsentgatewayjava.tests;

import com.github.lisama569.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lisama569.openbankingconsentgatewayjava.domain.Credential;
import com.github.lisama569.openbankingconsentgatewayjava.domain.CredentialStatus;
import com.github.lisama569.openbankingconsentgatewayjava.domain.PartnerClient;
import com.github.lisama569.openbankingconsentgatewayjava.service.ClientService;
import com.github.lisama569.openbankingconsentgatewayjava.store.ClientStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 合作伙伴接入与凭据轮换过渡期语义。 */
class ClientServiceTest {
    private static final Logger log = LoggerFactory.getLogger(ClientServiceTest.class);

    private MutableClock clock;
    private ClientService clientService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        GatewayProperties properties = new GatewayProperties();
        properties.setCredentialGraceSeconds(300);
        clientService = new ClientService(new ClientStore(), properties, clock);
    }

    @Test
    void onboardIsIdempotentAndIssuesActiveCredential() {
        PartnerClient first = clientService.onboard("client-1", "Partner One");
        PartnerClient again = clientService.onboard("client-1", "Partner One");
        log.info("case=onboard credentials={} same={}", first.getCredentials().size(), first == again);
        assertSame(first, again);
        assertEquals(1, first.getCredentials().size());
        assertEquals(CredentialStatus.ACTIVE, first.getCredentials().get(0).getStatus());
    }

    @Test
    void oldCredentialUsableDuringGracePeriodOnly() {
        PartnerClient client = clientService.onboard("client-1", "Partner One");
        Credential old = client.getCredentials().get(0);
        Credential fresh = clientService.rotateCredential("client-1");
        log.info("case=rotate old={} oldStatus={} new={} newStatus={}",
                old.getCredentialId(), old.getStatus(), fresh.getCredentialId(), fresh.getStatus());
        assertEquals(CredentialStatus.GRACE, old.getStatus());
        assertEquals(CredentialStatus.ACTIVE, fresh.getStatus());

        // 过渡期内：新旧凭据均可用
        boolean oldOk = clientService.authenticate("client-1", old.getCredentialId(), old.getSecret()).isPresent();
        boolean newOk = clientService.authenticate("client-1", fresh.getCredentialId(), fresh.getSecret()).isPresent();
        log.info("case=grace-window oldAccepted={} newAccepted={}", oldOk, newOk);
        assertTrue(oldOk, "过渡期内旧凭据须可用");
        assertTrue(newOk, "新凭据须立即可用");

        // 过渡期结束后：旧凭据不可用，新凭据仍可用
        clock.advanceSeconds(301);
        boolean oldAfter = clientService.authenticate("client-1", old.getCredentialId(), old.getSecret()).isPresent();
        boolean newAfter = clientService.authenticate("client-1", fresh.getCredentialId(), fresh.getSecret()).isPresent();
        log.info("case=grace-expired oldAccepted={} newAccepted={}", oldAfter, newAfter);
        assertFalse(oldAfter, "过渡期结束后旧凭据须不可用");
        assertTrue(newAfter);
    }

    @Test
    void secondRotationRetiresPreviousGraceCredential() {
        PartnerClient client = clientService.onboard("client-1", "Partner One");
        Credential gen1 = client.getCredentials().get(0);
        Credential gen2 = clientService.rotateCredential("client-1");
        Credential gen3 = clientService.rotateCredential("client-1");
        log.info("case=double-rotate gen1={} gen2={} gen3={}", gen1.getStatus(), gen2.getStatus(), gen3.getStatus());
        assertEquals(CredentialStatus.RETIRED, gen1.getStatus(), "再次轮换后最早的 GRACE 凭据直接退役");
        assertEquals(CredentialStatus.GRACE, gen2.getStatus(), "上一代凭据进入过渡期");
        assertEquals(CredentialStatus.ACTIVE, gen3.getStatus());

        boolean gen1Ok = clientService.authenticate("client-1", gen1.getCredentialId(), gen1.getSecret()).isPresent();
        boolean gen2Ok = clientService.authenticate("client-1", gen2.getCredentialId(), gen2.getSecret()).isPresent();
        log.info("case=double-rotate-auth gen1Accepted={} gen2Accepted={}", gen1Ok, gen2Ok);
        assertFalse(gen1Ok, "已退役凭据不可用");
        assertTrue(gen2Ok, "过渡期内上一代凭据仍可用");
    }

    @Test
    void rejectsBadSecretAndUnknownCredential() {
        PartnerClient client = clientService.onboard("client-1", "Partner One");
        Credential cred = client.getCredentials().get(0);
        boolean badSecret = clientService.authenticate("client-1", cred.getCredentialId(), "wrong").isPresent();
        boolean unknownCred = clientService.authenticate("client-1", "cred-missing", cred.getSecret()).isPresent();
        boolean unknownClient = clientService.authenticate("client-9", cred.getCredentialId(), cred.getSecret()).isPresent();
        log.info("case=reject badSecret={} unknownCred={} unknownClient={}", badSecret, unknownCred, unknownClient);
        assertFalse(badSecret);
        assertFalse(unknownCred);
        assertFalse(unknownClient);
    }
}
