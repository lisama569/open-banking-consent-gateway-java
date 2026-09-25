package com.github.lisama569.openbankingconsentgatewayjava;

import com.github.lisama569.openbankingconsentgatewayjava.service.PartnerService;
import com.github.lisama569.openbankingconsentgatewayjava.service.TokenRejectionReason;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 凭据轮换：宽限期内新旧凭据均可用，宽限期后旧凭据返回 CREDENTIAL_RETIRED。 */
class PartnerServiceTest {
    private static final Logger log = LoggerFactory.getLogger(PartnerServiceTest.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
    private final PartnerService partners = new PartnerService(clock);

    @Test
    void rotationGracePeriodSemantics() {
        partners.register("client-a", "Partner A");
        var initial = partners.issueInitialCredential("client-a");
        log.info("[test] 输入: clientId=client-a 初始凭据 credentialId={}", initial.credentialId());
        assertNull(partners.authenticate("client-a", initial.plainSecret()));

        var rotated = partners.rotateCredential("client-a", Duration.ofMinutes(5));
        log.info("[test] 轮换: 新凭据 credentialId={} 宽限期=5min", rotated.credentialId());

        // 宽限期内：新旧凭据都可用
        assertNull(partners.authenticate("client-a", rotated.plainSecret()), "新凭据应立即生效");
        assertNull(partners.authenticate("client-a", initial.plainSecret()), "宽限期内旧凭据仍可用");
        log.info("[test] 判定: 宽限期内新旧凭据均通过");

        // 宽限期过后：旧凭据 RETIRED，新凭据仍可用
        clock.advance(Duration.ofMinutes(6));
        TokenRejectionReason oldReason = partners.authenticate("client-a", initial.plainSecret());
        TokenRejectionReason newReason = partners.authenticate("client-a", rotated.plainSecret());
        log.info("[test] 判定: 宽限期后 旧凭据 -> {} 新凭据 -> {}", oldReason, newReason);
        assertEquals(TokenRejectionReason.CREDENTIAL_RETIRED, oldReason);
        assertNull(newReason);
    }

    @Test
    void authenticateDistinguishesRejectionReasons() {
        TokenRejectionReason unknown = partners.authenticate("ghost", "x");
        log.info("[test] 判定: 未知客户端 -> {}", unknown);
        assertEquals(TokenRejectionReason.UNKNOWN_CLIENT, unknown);

        partners.register("client-b", "Partner B");
        var cred = partners.issueInitialCredential("client-b");
        TokenRejectionReason bad = partners.authenticate("client-b", "wrong-secret");
        log.info("[test] 判定: 错误密钥 -> {}", bad);
        assertEquals(TokenRejectionReason.BAD_CREDENTIAL, bad);

        partners.find("client-b").orElseThrow().setSuspended(true);
        TokenRejectionReason suspended = partners.authenticate("client-b", cred.plainSecret());
        log.info("[test] 判定: 已暂停客户端 -> {}", suspended);
        assertEquals(TokenRejectionReason.CLIENT_SUSPENDED, suspended);
    }

    @Test
    void initialCredentialIssuedOnlyOnce() {
        partners.register("client-c", "Partner C");
        var first = partners.issueInitialCredential("client-c");
        var second = partners.issueInitialCredential("client-c");
        log.info("[test] 判定: 重复签发 credentialId 一致={} 第二次明文={}",
                first.credentialId().equals(second.credentialId()), second.plainSecret());
        assertEquals(first.credentialId(), second.credentialId());
        assertNull(second.plainSecret(), "明文只在首次签发时可见");
    }
}
