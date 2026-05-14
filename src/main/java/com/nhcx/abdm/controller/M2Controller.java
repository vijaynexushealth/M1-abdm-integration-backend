package com.nhcx.abdm.controller;

import com.nhcx.abdm.service.M2LinkingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * M2Controller — Triggers M2 outbound calls from your system to ABDM.
 * Patient-initiated callbacks are handled in CallbackController.
 */
@Slf4j
@RestController
@RequestMapping("/api/m2")
@RequiredArgsConstructor
@Tag(name = "M2 - Care Context Linking")
public class M2Controller {

    private final M2LinkingService m2Service;

    @PostMapping("/hip/fetch-auth-modes")
    @Operation(summary = "HIP Step 1: Fetch patient auth modes",
               description = "Body: { abhaAddress, hipId }. Async — await callback at /v0.5/users/auth/on-fetch-modes")
    public ResponseEntity<?> fetchAuthModes(@RequestBody Map<String, String> req) {
        try {
            m2Service.fetchAuthModes(req.get("abhaAddress"), req.get("hipId"));
            return ResponseEntity.accepted().body(Map.of("message", "Sent. Await callback: on-fetch-modes"));
        } catch (Exception e) {
            log.error("fetchAuthModes failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/hip/auth-init")
    @Operation(summary = "HIP Step 2: Init auth — triggers OTP to patient",
               description = "Body: { abhaAddress, hipId, authMode }. authMode: MOBILE_OTP or AADHAAR_OTP")
    public ResponseEntity<?> authInit(@RequestBody Map<String, String> req) {
        try {
            m2Service.initAuthRequest(req.get("abhaAddress"), req.get("hipId"),
                    req.getOrDefault("authMode", "MOBILE_OTP"));
            return ResponseEntity.accepted().body(Map.of("message", "Sent. Await callback: on-auth-init"));
        } catch (Exception e) {
            log.error("authInit failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/hip/auth-confirm")
    @Operation(summary = "HIP Step 3: Submit patient OTP",
               description = "Body: { transactionId, otp, hipId }. transactionId from on-auth-init callback")
    public ResponseEntity<?> authConfirm(@RequestBody Map<String, String> req) {
        try {
            m2Service.confirmAuthRequest(req.get("transactionId"), req.get("otp"), req.get("hipId"));
            return ResponseEntity.accepted().body(Map.of("message", "Sent. Await linkToken at: on-auth-confirm"));
        } catch (Exception e) {
            log.error("authConfirm failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/hip/add-care-context")
    @Operation(summary = "HIP Step 4: Link care contexts using linkToken",
               description = "Body: { linkToken, patientRef, patientDisplay, hipId, careContexts: [{referenceNumber, display}] }")
    public ResponseEntity<?> addCareContext(@RequestBody Map<String, Object> req) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> careContexts = (List<Map<String, String>>) req.get("careContexts");
            m2Service.addCareContext(
                    (String) req.get("linkToken"),
                    (String) req.get("patientRef"),
                    (String) req.get("patientDisplay"),
                    careContexts,
                    (String) req.get("hipId")
            );
            return ResponseEntity.accepted().body(Map.of("message", "Sent. Await callback: on-add-care-context"));
        } catch (Exception e) {
            log.error("addCareContext failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/hiu/consent-init")
    @Operation(summary = "HIU: Initiate consent request",
               description = "Body: { patientAbhaAddress, hiuId, hipId }")
    public ResponseEntity<?> consentInit(@RequestBody Map<String, String> req) {
        try {
            m2Service.initiateConsent(req.get("patientAbhaAddress"), req.get("hiuId"), req.get("hipId"));
            return ResponseEntity.accepted().body(Map.of("message", "Consent request sent to patient."));
        } catch (Exception e) {
            log.error("consentInit failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/hiu/health-info-request")
    @Operation(summary = "HIU: Request health records after consent approval",
               description = "Body: { consentId, consentArtefactId, hiuId }")
    public ResponseEntity<?> healthInfoRequest(@RequestBody Map<String, String> req) {
        try {
            m2Service.requestHealthInfo(req.get("consentId"), req.get("consentArtefactId"), req.get("hiuId"));
            return ResponseEntity.accepted().body(Map.of("message", "Health info request sent."));
        } catch (Exception e) {
            log.error("healthInfoRequest failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
