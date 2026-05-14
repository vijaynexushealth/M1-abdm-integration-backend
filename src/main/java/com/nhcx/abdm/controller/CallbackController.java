package com.nhcx.abdm.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhcx.abdm.service.M2LinkingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CallbackController — Receives ALL async callbacks from ABDM gateway.
 * Register your server URL via: POST /api/m1/bridge/register
 * Use ngrok locally: ngrok http 8080 → register <a href="https://xyz.ngrok.io">...</a>
 * ABDM will POST to these paths on your registered bridge URL.
 * All callbacks must return HTTP 200.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Callbacks - ABDM Async Callbacks")
public class CallbackController {

    private final M2LinkingService m2Service;

    // ── HIP-Initiated Linking Callbacks ──────────────────────────────────────

    @PostMapping("/v0.5/users/auth/on-fetch-modes")
    @Operation(summary = "Callback: Patient auth modes from ABDM")
    public ResponseEntity<Void> onFetchModes(@RequestBody JsonNode body) {
        log.info("=== on-fetch-modes ===\n{}", body.toPrettyString());
        // Extract: body.auth.modes (array), body.auth.transactionId
        // → Use transactionId to call POST /api/m2/hip/auth-init
        return ResponseEntity.ok().build();
    }

    @PostMapping("/v0.5/users/auth/on-init")
    @Operation(summary = "Callback: Auth init confirmed — OTP sent to patient")
    public ResponseEntity<Void> onAuthInit(@RequestBody JsonNode body) {
        log.info("=== on-auth-init ===\n{}", body.toPrettyString());
        String transactionId = body.path("auth").path("transactionId").asText();
        log.info("transactionId for confirm: {}", transactionId);
        // → Show OTP entry to patient, then call POST /api/m2/hip/auth-confirm
        return ResponseEntity.ok().build();
    }

    @PostMapping("/v0.5/users/auth/on-confirm")
    @Operation(summary = "Callback: Auth confirmed — linkToken received")
    public ResponseEntity<Void> onAuthConfirm(@RequestBody JsonNode body) {
        log.info("=== on-auth-confirm ===\n{}", body.toPrettyString());
        String linkToken   = body.path("auth").path("accessToken").asText();
        String patientAbha = body.path("auth").path("patient").path("id").asText();
        log.info("linkToken for patient {}: {}", patientAbha, linkToken);
        // → Call POST /api/m2/hip/add-care-context with this linkToken
        return ResponseEntity.ok().build();
    }

    @PostMapping("/v0.5/links/add-contexts/on-add")
    @Operation(summary = "Callback: Care context linking result")
    public ResponseEntity<Void> onAddCareContext(@RequestBody JsonNode body) {
        log.info("=== on-add-care-context ===\n{}", body.toPrettyString());
        String status = body.path("acknowledgement").path("status").asText();
        if ("SUCCESS".equals(status)) {
            log.info("Care context linked successfully!");
        } else {
            log.warn("Care context linking failed: {}", body.path("error").toPrettyString());
        }
        return ResponseEntity.ok().build();
    }

    // ── Patient-Initiated: Discovery (SYNC in V3) ─────────────────────────────

    @PostMapping("/v0.5/care-contexts/discover")
    @Operation(summary = "Callback: ABDM asks HIP to find patient (returns sync response in V3)")
    public ResponseEntity<String> onDiscover(@RequestBody JsonNode body,
                                             @RequestHeader(value = "X-HIP-ID", required = false) String hipId) {
        log.info("=== care-contexts/discover === HIP={}\n{}", hipId, body.toPrettyString());
        try {
            String requestId     = body.path("requestId").asText();
            String transactionId = body.path("transactionId").asText();
            JsonNode patient     = body.path("patient");

            log.info("Finding patient: name={}, gender={}, yob={}",
                    patient.path("name").asText(),
                    patient.path("gender").asText(),
                    patient.path("yearOfBirth").asInt());

            // TODO: Query your HIS/EMR to match the patient
            // For sandbox testing — return mock care contexts:
            List<Map<String, String>> careContexts = new ArrayList<>();
            careContexts.add(Map.of("referenceNumber", "CC-001", "display", "OPD Visit - 2024-01-15"));
            careContexts.add(Map.of("referenceNumber", "CC-002", "display", "Lab Report - 2024-02-10"));

            String response = m2Service.buildDiscoverResponse(
                    requestId, transactionId,
                    "PT-" + System.currentTimeMillis(),
                    patient.path("name").asText("Test Patient"),
                    careContexts
            );
            return ResponseEntity.ok().header("Content-Type", "application/json").body(response);
        } catch (Exception e) {
            log.error("Discovery failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Patient-Initiated: Link Init ──────────────────────────────────────────

    @PostMapping("/v0.5/links/link/init")
    @Operation(summary = "Callback: ABDM asks HIP to send OTP to patient")
    public ResponseEntity<String> onLinkInit(@RequestBody JsonNode body,
                                             @RequestHeader(value = "X-HIP-ID", required = false) String hipId) {
        log.info("=== links/link/init === HIP={}\n{}", hipId, body.toPrettyString());
        try {
            String requestId     = body.path("requestId").asText();
            String transactionId = body.path("transactionId").asText();
            String linkRefNum    = "LR-" + System.currentTimeMillis();

            // TODO: Generate OTP and send to patient's mobile
            log.info("Sending OTP to patient, linkRef: {}", linkRefNum);

            String response = m2Service.buildLinkInitResponse(requestId, transactionId, linkRefNum);
            return ResponseEntity.ok().header("Content-Type", "application/json").body(response);
        } catch (Exception e) {
            log.error("LinkInit failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Patient-Initiated: Link Confirm ───────────────────────────────────────

    @PostMapping("/v0.5/links/link/confirm")
    @Operation(summary = "Callback: Patient submitted OTP — verify and return care contexts")
    public ResponseEntity<String> onLinkConfirm(@RequestBody JsonNode body,
                                                @RequestHeader(value = "X-HIP-ID", required = false) String hipId) {
        log.info("=== links/link/confirm === HIP={}\n{}", hipId, body.toPrettyString());
        try {
            String requestId  = body.path("requestId").asText();
            String linkRef    = body.path("confirmation").path("linkRefNumber").asText();
            String patientOtp = body.path("confirmation").path("token").asText();

            log.info("Verifying OTP for linkRef={}, otp={}", linkRef, patientOtp);
            // TODO: Verify OTP against what you sent in link/init
            // For sandbox: accept any OTP

            List<Map<String, String>> careContexts = List.of(
                    Map.of("referenceNumber", "CC-001", "display", "OPD Visit - 2024-01-15"),
                    Map.of("referenceNumber", "CC-002", "display", "Lab Report - 2024-02-10")
            );

            String response = m2Service.buildLinkConfirmResponse(
                    requestId, "PT-TEST-001", "Test Patient", careContexts);
            return ResponseEntity.ok().header("Content-Type", "application/json").body(response);
        } catch (Exception e) {
            log.error("LinkConfirm failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Consent Callbacks ─────────────────────────────────────────────────────

    @PostMapping("/v0.5/consent-requests/hiu/notify")
    @Operation(summary = "Callback: Patient approved/denied consent (HIU)")
    public ResponseEntity<Void> onConsentNotifyHIU(@RequestBody JsonNode body) {
        log.info("=== consent/hiu/notify ===\n{}", body.toPrettyString());
        String status    = body.path("notification").path("status").asText();
        String consentId = body.path("notification").path("consentRequestId").asText();
        log.info("Consent [{}] status: {}", consentId, status);
        // If GRANTED → call POST /api/m2/hiu/health-info-request with artefact IDs
        return ResponseEntity.ok().build();
    }

    @PostMapping("/v0.5/consents/hip/notify")
    @Operation(summary = "Callback: HIP notified of consent artefact")
    public ResponseEntity<Void> onConsentNotifyHIP(@RequestBody JsonNode body) {
        log.info("=== consent/hip/notify ===\n{}", body.toPrettyString());
        return ResponseEntity.ok().build();
    }

    // ── Health Data Callbacks ─────────────────────────────────────────────────

    @PostMapping("/v0.5/health-information/hip/request")
    @Operation(summary = "Callback: ABDM asks HIP to push health data")
    public ResponseEntity<Void> onHealthInfoRequest(@RequestBody JsonNode body) {
        log.info("=== health-information/hip/request ===\n{}", body.toPrettyString());
        String dataPushUrl = body.path("hiRequest").path("dataPushUrl").asText();
        log.info("Push encrypted FHIR bundle to: {}", dataPushUrl);
        // TODO: Fetch FHIR data from HIS, encrypt with Fidelius, push to dataPushUrl
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/m2/callback/health-data")
    @Operation(summary = "Callback: Receive encrypted FHIR health data from HIP")
    public ResponseEntity<Void> receiveHealthData(@RequestBody JsonNode body) {
        log.info("=== health data received ===\n{}", body.toPrettyString());
        // TODO: Decrypt with Fidelius using your ECDH private key, process FHIR bundle
        return ResponseEntity.ok().build();
    }

    @PostMapping("/v3/hip/patient/profile/on-share")
    @Operation(summary = "Callback: Patient profile shared via QR scan")
    public ResponseEntity<Void> onProfileShare(@RequestBody JsonNode body,
                                               @RequestHeader(value = "X-HIP-ID", required = false) String hipId) {
        log.info("=== patient/profile/on-share === HIP={}\n{}", hipId, body.toPrettyString());
        JsonNode profile = body.path("profile").path("patient");
        log.info("Patient ABHA: {}, Name: {}",
                profile.path("healthId").asText(), profile.path("name").asText());
        return ResponseEntity.ok().build();
    }
}
