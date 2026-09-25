package com.github.lisama569.openbankingconsentgatewayjava.web;

import com.github.lisama569.openbankingconsentgatewayjava.domain.Credential;
import com.github.lisama569.openbankingconsentgatewayjava.domain.PartnerClient;
import com.github.lisama569.openbankingconsentgatewayjava.service.ClientService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 合作伙伴接入与凭据轮换入口。 */
@RestController
@RequestMapping("/api/clients")
public class ClientController {
    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    public record OnboardRequest(String clientId, String name) {}
    public record CredentialView(String credentialId, String secret, String status) {}
    public record ClientView(String clientId, String name, List<CredentialView> credentials) {}
    public record AuthenticateRequest(String clientId, String credentialId, String secret) {}

    @PostMapping
    public ClientView onboard(@RequestBody OnboardRequest request) {
        return toView(clientService.onboard(request.clientId(), request.name()));
    }

    @PostMapping("/{clientId}/credentials/rotate")
    public ResponseEntity<CredentialView> rotate(@PathVariable String clientId) {
        try {
            Credential fresh = clientService.rotateCredential(clientId);
            return ResponseEntity.ok(new CredentialView(fresh.getCredentialId(), fresh.getSecret(), fresh.getStatus().name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/authenticate")
    public ResponseEntity<Map<String, Object>> authenticate(@RequestBody AuthenticateRequest request) {
        return clientService.authenticate(request.clientId(), request.credentialId(), request.secret())
                .<ResponseEntity<Map<String, Object>>>map(c -> ResponseEntity.ok(Map.of("authenticated", true, "clientId", c.getClientId())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("authenticated", false, "reason", "invalid-or-unusable-credential")));
    }

    private static ClientView toView(PartnerClient client) {
        List<CredentialView> creds = client.getCredentials().stream()
                .map(c -> new CredentialView(c.getCredentialId(), c.getSecret(), c.getStatus().name()))
                .toList();
        return new ClientView(client.getClientId(), client.getName(), creds);
    }
}
