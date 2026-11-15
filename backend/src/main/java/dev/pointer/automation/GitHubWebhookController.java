package dev.pointer.automation;

import dev.pointer.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/webhooks/github")
@RequiredArgsConstructor
class GitHubWebhookController {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String ISSUES_EVENT = "issues";
    private static final String LABELED = "labeled";

    private final AutomationService service;
    private final JsonMapper json;

    @PostMapping("/{automationId}")
    ResponseEntity<Map<String, Object>> receive(
            @PathVariable UUID automationId,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(name = "X-GitHub-Event", required = false) String event,
            @RequestBody byte[] body) {
        var automation = service.find(automationId)
                .orElseThrow(() -> ApiException.notFound("automation_not_found", "Automation does not exist"));
        if (!isSigned(body, signature, service.webhookSecret(automation))) {
            throw ApiException.unauthorized("invalid_signature", "The webhook signature is invalid");
        }
        if (!ISSUES_EVENT.equals(event) || !automation.isEnabled()) {
            return ResponseEntity.noContent().build();
        }
        var payload = parse(body);
        var label = payload.path("label").path("name").asString("");
        var issue = payload.path("issue");
        if (!LABELED.equals(payload.path("action").asString(""))
                || !automation.getTriggerLabel().equalsIgnoreCase(label)
                || !issue.path("number").isIntegralNumber()
                || issue.has("pull_request")) {
            return ResponseEntity.noContent().build();
        }
        return service.startFromLabel(
                        automation,
                        issue.path("number").asInt(),
                        issue.path("title")
                                .asString("Issue #" + issue.path("number").asInt()))
                .map(runId -> ResponseEntity.accepted().body(Map.<String, Object>of("runId", runId)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private JsonNode parse(byte[] body) {
        try {
            return json.readTree(body);
        } catch (JacksonException exception) {
            throw ApiException.badRequest("invalid_payload", "The webhook payload is not valid JSON");
        }
    }

    static boolean isSigned(byte[] body, String signature, String secret) {
        if (signature == null || !signature.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(body, secret).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    }

    static String sign(byte[] body, String secret) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }
}
