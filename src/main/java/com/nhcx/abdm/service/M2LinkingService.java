package com.nhcx.abdm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhcx.abdm.config.AbdmConfig;
import com.nhcx.abdm.util.HeaderUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * M2LinkingService — Milestone 2: Care Context Linking & Health Data
 *
 * HIP-Initiated Linking (your server → ABDM):
 *   fetchAuthModes()     → async callback: on-fetch-modes
 *   initAuthRequest()    → async callback: on-init  (OTP sent to patient)
 *   confirmAuthRequest() → async callback: on-confirm (linkToken)
 *   addCareContext()     → uses linkToken from on-confirm
 *
 * Patient-Initiated (ABDM → your server):
 *   buildDiscoverResponse()    → synchronous response to /v0.5/care-contexts/discover
 *   buildLinkInitResponse()    → response to /v0.5/links/link/init
 *   buildLinkConfirmResponse() → response to /v0.5/links/link/confirm
 *
 * HIU Consent + Data:
 *   initiateConsent()    → POST /consent-requests/init
 *   requestHealthInfo()  → POST /health-information/cm/request
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class M2LinkingService {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final AbdmConfig   config;
    private final AuthService  authService;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // ── HIP-Initiated: Fetch Auth Modes ─────────────────────────────────────

    public void fetchAuthModes(String abhaAddress, String hipId) throws Exception {
        String token     = authService.getAccessToken();
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("timestamp", Instant.now().toString());
        body.put("query", Map.of("id", abhaAddress, "purpose", "LINK"));

        String url = config.getGatewayBaseUrl() + "/api/hiecm/gateway/v3/users/auth/fetch-modes";
        post(url, token, hipId, body);
        log.info("FetchAuthModes sent [requestId={}]", requestId);
    }

    // ── HIP-Initiated: Auth Init ─────────────────────────────────────────────

    public void initAuthRequest(String abhaAddress, String hipId, String authMode) throws Exception {
        String token     = authService.getAccessToken();
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("timestamp", Instant.now().toString());
        body.put("query", Map.of(
                "id",       abhaAddress,
                "purpose",  "LINK",
                "authMode", authMode,
                "requester", Map.of("type", "HIP", "id", hipId)
        ));

        String url = config.getGatewayBaseUrl() + "/api/hiecm/gateway/v3/users/auth/init";
        post(url, token, hipId, body);
        log.info("AuthInit sent [requestId={}]", requestId);
    }

    // ── HIP-Initiated: Auth Confirm ──────────────────────────────────────────

    public void confirmAuthRequest(String transactionId, String otp, String hipId) throws Exception {
        String token     = authService.getAccessToken();
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId",     requestId);
        body.put("timestamp",     Instant.now().toString());
        body.put("transactionId", transactionId);
        body.put("credential",    Map.of("authCode", otp));

        String url = config.getGatewayBaseUrl() + "/api/hiecm/gateway/v3/users/auth/confirm";
        post(url, token, hipId, body);
        log.info("AuthConfirm sent [requestId={}]", requestId);
    }

    // ── HIP-Initiated: Add Care Context ──────────────────────────────────────

    public void addCareContext(String linkToken, String patientRef, String patientDisplay,
                               List<Map<String, String>> careContexts, String hipId) throws Exception {
        String token     = authService.getAccessToken();
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("referenceNumber", patientRef);
        patient.put("display",         patientDisplay);
        patient.put("careContexts",    careContexts);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("timestamp", Instant.now().toString());
        body.put("link", Map.of("accessToken", linkToken, "patient", patient));

        post(config.addCareContextUrl(), token, hipId, body);
        log.info("AddCareContext sent [requestId={}]", requestId);
    }

    // ── Patient-Initiated: Discover Response (SYNC in V3) ────────────────────

    public String buildDiscoverResponse(String requestId, String transactionId,
                                        String patientRef, String patientDisplay,
                                        List<Map<String, String>> careContexts) throws Exception {
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("referenceNumber", patientRef);
        patient.put("display",         patientDisplay);
        patient.put("careContexts",    careContexts);
        patient.put("matchedBy",       List.of("MOBILE"));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("requestId",     UUID.randomUUID().toString());
        resp.put("timestamp",     Instant.now().toString());
        resp.put("transactionId", transactionId);
        resp.put("patient",       patient);
        resp.put("resp",          Map.of("requestId", requestId));
        return objectMapper.writeValueAsString(resp);
    }

    // ── Patient-Initiated: Link Init Response ────────────────────────────────

    public String buildLinkInitResponse(String requestId, String transactionId,
                                        String linkRefNumber) throws Exception {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("referenceNumber",    linkRefNumber);
        link.put("authenticationType", "DIRECT");
        link.put("meta", Map.of(
                "communicationMedium", "MOBILE",
                "communicationHint",   "OTP sent to registered mobile",
                "communicationExpiry", Instant.now().plusSeconds(600).toString()
        ));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("requestId",     UUID.randomUUID().toString());
        resp.put("timestamp",     Instant.now().toString());
        resp.put("transactionId", transactionId);
        resp.put("link",          link);
        resp.put("resp",          Map.of("requestId", requestId));
        return objectMapper.writeValueAsString(resp);
    }

    // ── Patient-Initiated: Link Confirm Response ─────────────────────────────

    public String buildLinkConfirmResponse(String requestId, String patientRef,
                                           String patientDisplay,
                                           List<Map<String, String>> careContexts) throws Exception {
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("referenceNumber", patientRef);
        patient.put("display",         patientDisplay);
        patient.put("careContexts",    careContexts);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("requestId", UUID.randomUUID().toString());
        resp.put("timestamp", Instant.now().toString());
        resp.put("patient",   patient);
        resp.put("resp",      Map.of("requestId", requestId));
        return objectMapper.writeValueAsString(resp);
    }

    // ── HIU: Initiate Consent ─────────────────────────────────────────────────

    public void initiateConsent(String patientAbhaAddress, String hiuId, String hipId) throws Exception {
        String token     = authService.getAccessToken();
        String requestId = UUID.randomUUID().toString();

        Map<String, Object> consent = new LinkedHashMap<>();
        consent.put("purpose",   Map.of("text", "Self Requested", "code", "SELFPAY", "refUri", ""));
        consent.put("patient",   Map.of("id", patientAbhaAddress));
        consent.put("hiu",       Map.of("id", hiuId));
        consent.put("requester", Map.of("name", "HIU System",
                "identifier", Map.of("type", "REGNO", "value", hiuId, "system", "https://www.mciindia.org")));
        consent.put("hiTypes",   List.of("OPConsultation", "DiagnosticReport", "Prescription",
                "DischargeSummary", "ImmunizationRecord", "HealthDocumentRecord"));
        consent.put("permission", Map.of(
                "accessMode", "VIEW",
                "dateRange", Map.of("from", "2020-01-01T00:00:00.000Z", "to", Instant.now().toString()),
                "dataEraseAt", Instant.now().plusSeconds(86400 * 30).toString(),
                "frequency", Map.of("unit", "HOUR", "value", 1, "repeats", 0)
        ));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("timestamp", Instant.now().toString());
        body.put("consent",   consent);

        post(config.consentInitUrl(), token, hiuId, body);
        log.info("Consent init sent [requestId={}]", requestId);
    }

    // ── HIU: Request Health Info ──────────────────────────────────────────────

    public void requestHealthInfo(String consentId, String consentArtefactId, String hiuId) throws Exception {
        String token     = authService.getAccessToken();
        String requestId = UUID.randomUUID().toString();
        String nonce     = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());

        Map<String, Object> hiRequest = new LinkedHashMap<>();
        hiRequest.put("consent",     Map.of("id", consentArtefactId));
        hiRequest.put("dateRange",   Map.of("from", "2020-01-01T00:00:00.000Z", "to", Instant.now().toString()));
        hiRequest.put("dataPushUrl", "https://your-server.io/api/m2/callback/health-data");
        hiRequest.put("keyMaterial", Map.of(
                "cryptoAlg", "ECDH", "curve", "Curve25519",
                "dhPublicKey", Map.of(
                        "expiry", Instant.now().plusSeconds(3600).toString(),
                        "parameters", "Curve25519/32byte random key",
                        "keyValue", nonce
                ),
                "nonce", nonce
        ));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("timestamp", Instant.now().toString());
        body.put("hiRequest", hiRequest);

        post(config.healthInfoRequestUrl(), token, hiuId, body);
        log.info("HealthInfo request sent [requestId={}]", requestId);
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private void post(String url, String accessToken, String hipId, Object body) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);

        Request.Builder builder = HeaderUtil.withAuth(new Request.Builder().url(url), accessToken, config.getXCmId());
        if (hipId != null) builder.header("X-HIP-ID", hipId);

        Request request = builder.post(RequestBody.create(jsonBody, JSON)).build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            // ABDM returns 202 Accepted for async calls
            if (response.code() != 200 && response.code() != 202) {
                log.error("POST {} → [{}] {}", url, response.code(), responseBody);
                throw new RuntimeException("API failed [" + response.code() + "]: " + responseBody);
            }
        }
    }
}
