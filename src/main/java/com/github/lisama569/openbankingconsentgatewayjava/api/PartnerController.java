package com.github.lisama569.openbankingconsentgatewayjava.api;

import com.github.lisama569.openbankingconsentgatewayjava.domain.PartnerClient;
import com.github.lisama569.openbankingconsentgatewayjava.service.PartnerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 合作伙伴接入与凭据轮换入口。 */
@RestController
@RequestMapping("/api/partners")
public class PartnerController {
    private final PartnerService partnerService;

    public PartnerController(PartnerService partnerService) {
        this.partnerService = partnerService;
    }

    /** 注册客户端并签发初始凭据（明文仅此一次返回）。 */
    @PostMapping
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String clientId = body.get("clientId");
        String name = body.getOrDefault("name", clientId);
        PartnerClient client = partnerService.register(clientId, name);
        PartnerService.IssuedCredential issued = partnerService.issueInitialCredential(clientId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("clientId", client.getClientId());
        resp.put("name", client.getName());
        resp.put("credentialId", issued.credentialId());
        resp.put("secret", issued.plainSecret()); // 幂等命中时为 null，表示明文不可恢复
        return resp;
    }

    /** 轮换凭据：新凭据立即生效，旧凭据进入 gracePeriodSeconds 宽限期。 */
    @PostMapping("/{clientId}/credentials/rotate")
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable String clientId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        long graceSeconds = body == null ? 300
                : ((Number) body.getOrDefault("gracePeriodSeconds", 300)).longValue();
        try {
            PartnerService.IssuedCredential issued = partnerService.rotateCredential(clientId, Duration.ofSeconds(graceSeconds));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("clientId", clientId);
            resp.put("credentialId", issued.credentialId());
            resp.put("secret", issued.plainSecret());
            resp.put("gracePeriodSeconds", graceSeconds);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "UNKNOWN_CLIENT", "clientId", clientId));
        }
    }
}
