package com.nhcx.abdm.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhcx.abdm.config.AbdmConfig;
import com.nhcx.abdm.service.AuthService;
import com.nhcx.abdm.service.EncryptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.RequestBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AbdmStepController
 *
 * Each step is a separate API — call them in order:
 *
 *   Step 1:  GET  /api/step/session          → get access token
 *   Step 2:  GET  /api/step/cert             → get RSA public key
 *   Step 3:  POST /api/step/encrypt          → encrypt any value (Aadhaar / OTP / Mobile)
 *   Step 4:  POST /api/step/send-otp         → encrypt Aadhaar + call ABDM send OTP
 *   Step 5:  POST /api/step/verify-otp       → encrypt OTP + call ABDM create ABHA
 *   Step 6:  GET  /api/step/suggestions      → get ABHA address suggestions
 *   Step 7:  POST /api/step/create-address   → create preferred ABHA address
 *   Step 8:  GET  /api/step/profile          → get ABHA profile
 */
@Slf4j
@RestController
@RequestMapping("/api/step")
@RequiredArgsConstructor
@Tag(name = "ABDM M1 Steps - ABHA M1 Milestone ")
public class AbdmStepController {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private final AuthService       authService;
    private final EncryptionService encryptionService;
    private final AbdmConfig        config;
    private final OkHttpClient      httpClient;
    private final ObjectMapper      objectMapper;

    // ── STEP 1: Session API ───────────────────────────────────────────────────

    @GetMapping("/session")
    @Operation(
            summary     = "Step 1 — Get Access Token",
            description = "Calls ABDM Gateway session API and returns the Bearer token. " +
                    "Equivalent curl:\n\n" +
                    "curl --location 'https://dev.abdm.gov.in/api/hiecm/gateway/v3/sessions'\n" +
                    "--header 'Content-Type: application/json'\n" +
                    "--header 'REQUEST-ID: <uuid>'\n" +
                    "--header 'TIMESTAMP: <iso>'\n" +
                    "--header 'X-CM-ID: sbx'\n" +
                    "--data '{\"clientId\":\"SBXID_xxx\",\"clientSecret\":\"xxx\",\"grantType\":\"client_credentials\"}'"
    )
    public ResponseEntity<?> getSession() {
        try {
            String token = authService.getAccessToken();
            log.info("=== STEP 1: Session OK ===");
            return ResponseEntity.ok(Map.of(
                    "step",        "1 - Session API",
                    "status",      "SUCCESS",
                    "accessToken", token,
                    "clientId",    config.getClientId(),
                    "url",         config.sessionUrl()
            ));
        } catch (Exception e) {
            log.error("Step 1 FAILED", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "1 - Session API",
                    "error", e.getMessage()
            ));
        }
    }

    // ── STEP 2: Cert API ─────────────────────────────────────────────────────

    @GetMapping("/cert")
    @Operation(
            summary     = "Step 2 — Get RSA Public Certificate",
            description = "Fetches ABDM public certificate used to encrypt Aadhaar/OTP/Mobile. " +
                    "Equivalent curl:\n\n" +
                    "curl --location 'https://abhasbx.abdm.gov.in/abha/api/v3/profile/public/certificate'\n" +
                    "--header 'REQUEST-ID: <uuid>'\n" +
                    "--header 'TIMESTAMP: <iso>'\n" +
                    "--header 'Authorization: Bearer <token>'"
    )
    public ResponseEntity<?> getCert() {
        try {
            String publicKey = encryptionService.fetchPublicKeyString();
            log.info("=== STEP 2: Cert API OK ===");
            return ResponseEntity.ok(Map.of(
                    "step",           "2 - Cert API",
                    "status",         "SUCCESS",
                    "publicKey",      publicKey,
                    "algorithm",      "RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
                    "usage",          "Encrypt Aadhaar / Mobile / OTP before sending to ABDM",
                    "certUrl",        config.publicCertUrl()
            ));
        } catch (Exception e) {
            log.error("Step 2 FAILED", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "2 - Cert API",
                    "error", e.getMessage()
            ));
        }
    }

    // ── STEP 3: Encrypt any value ─────────────────────────────────────────────

    @PostMapping("/encrypt")
    @Operation(
            summary     = "Step 3 — Encrypt value using ABDM public key",
            description = "Encrypts any sensitive value (Aadhaar, Mobile, OTP) using RSA/ECB/OAEPWithSHA-1AndMGF1Padding. " +
                    "Body: { \"value\": \"your-aadhaar-or-otp\" }"
    )
    public ResponseEntity<?> encrypt(@org.springframework.web.bind.annotation.RequestBody Map<String, String> req) {
        try {
            String plaintext  = req.get("value");
            String encrypted  = encryptionService.encrypt(plaintext);
            log.info("=== STEP 3: Encrypt OK ===");
            return ResponseEntity.ok(Map.of(
                    "step",          "3 - Encrypt",
                    "status",        "SUCCESS",
                    "plainLength",   plaintext.length(),
                    "encryptedValue", encrypted,
                    "algorithm",     "RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
                    "note",          "Use encryptedValue as loginId or otpValue in ABDM APIs"
            ));
        } catch (Exception e) {
            log.error("Step 3 FAILED", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "3 - Encrypt",
                    "error", e.getMessage()
            ));
        }
    }

    // ── STEP 4: Send OTP (Aadhaar enrollment) ────────────────────────────────

    @PostMapping("/send-otp")
    @Operation(
            summary     = "Step 4 — Send OTP to Aadhaar-linked mobile",
            description = "Internally: (1) gets token, (2) fetches cert, (3) encrypts Aadhaar, " +
                    "(4) calls ABDM /enrollment/request/otp with encrypted loginId. " +
                    "Body: { \"aadhaarNumber\": \"your-12-digit-aadhaar\" }\n\n" +
                    "Equivalent curl after encryption:\n\n" +
                    "curl --location 'https://abhasbx.abdm.gov.in/abha/api/v3/enrollment/request/otp'\n" +
                    "--header 'Authorization: Bearer <token>'\n" +
                    "--header 'REQUEST-ID: <uuid>'\n" +
                    "--header 'TIMESTAMP: <iso>'\n" +
                    "--data '{\"txnId\":\"\",\"scope\":[\"abha-enrol\"],\"loginHint\":\"aadhaar\"," +
                    "\"loginId\":\"<ENCRYPTED_AADHAAR>\",\"otpSystem\":\"aadhaar\"}'"
    )
    public ResponseEntity<?> sendOtp(@org.springframework.web.bind.annotation.RequestBody Map<String, String> req) {
        try {
            String aadhaar = req.get("aadhaarNumber");
            if (aadhaar == null || aadhaar.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "aadhaarNumber is required"));
            }

            // Step 1: Get token
            String token = authService.getAccessToken();
            log.info("=== STEP 4: Send OTP → Got token ===");

            // Step 2 & 3: Get cert + Encrypt Aadhaar
            String encryptedAadhaar = encryptionService.encrypt(aadhaar);
            log.info("Aadhaar encrypted successfully");

            // Step 4: Call ABDM
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("txnId",     "");
            body.put("scope",     List.of("abha-enrol"));
            body.put("loginHint", "aadhaar");
            body.put("loginId",   encryptedAadhaar);
            body.put("otpSystem", "aadhaar");

            String url = config.enrolOtpUrl();
            log.info("Calling ABDM Send OTP → {}", url);
            log.info("loginId (encrypted, first 40): {}...", encryptedAadhaar.substring(0, 40));

            JsonNode abdmResponse = post(url, token, body);

            String txnId = abdmResponse.path("txnId").asText();
            log.info("=== STEP 4: OTP Sent! txnId={} ===", txnId);

            return ResponseEntity.ok(Map.of(
                    "step",             "4 - Send OTP",
                    "status",           "SUCCESS",
                    "txnId",            txnId,
                    "message",          abdmResponse.path("message").asText(),
                    "encryptedAadhaar", encryptedAadhaar,
                    "nextStep",         "Call POST /api/step/verify-otp with { txnId, otp, mobile }"
            ));
        } catch (Exception e) {
            log.error("Step 4 FAILED", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "4 - Send OTP",
                    "error", e.getMessage()
            ));
        }
    }

    // ── STEP 5: Verify OTP → Create ABHA ─────────────────────────────────────

    @PostMapping("/verify-otp")
    @Operation(
            summary     = "Step 5 — Verify OTP and create ABHA",
            description = "Body: { \"txnId\": \"from-step-4\", \"otp\": \"6-digit-otp\", \"mobile\": \"10-digit-aadhaar-linked-mobile\" }\n\n" +
                    "IMPORTANT: mobile is REQUIRED by ABDM and must be the exact 10-digit mobile linked to your Aadhaar (no +91, no spaces).\n\n" +
                    "Example: { \"txnId\": \"abc-123\", \"otp\": \"123456\", \"mobile\": \"9876543210\" }\n\n" +
                    "Both OTP and mobile are RSA-encrypted automatically. Returns xToken for Steps 6-8."
    )
    public ResponseEntity<?> verifyOtp(@org.springframework.web.bind.annotation.RequestBody Map<String, String> req) {
        try {
            String txnId  = req.get("txnId");
            String otp    = req.get("otp");
            String mobile = req.get("mobile");

            if (txnId == null || txnId.isBlank() || otp == null || otp.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "txnId and otp are required",
                        "hint",  "mobile is also required — use the 10-digit number linked to your Aadhaar (no +91, no spaces)"));
            }

            if (mobile == null || mobile.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "mobile is required by ABDM for enrol/byAadhaar",
                        "hint",  "Provide the exact 10-digit mobile number registered with your Aadhaar (e.g. 9876543210)"));
            }

            // Validate mobile format: 10 digits only
            String cleanMobile = mobile.trim().replaceAll("\\s+", "");
            if (!cleanMobile.matches("[6-9]\\d{9}")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid mobile format: " + cleanMobile,
                        "hint",  "Must be exactly 10 digits starting with 6, 7, 8 or 9 — no +91, no spaces"));
            }

            String token        = authService.getAccessToken();
            String encryptedOtp = encryptionService.encrypt(otp);
            log.info("=== STEP 5: OTP encrypted. Mobile (plain, last 4): {}",
                    cleanMobile.substring(cleanMobile.length() - 4));

            // Build exactly as per official ABDM doc:
            // {
            //   "authData": {
            //     "authMethods": ["otp"],
            //     "otp": {
            //       "txnId": "...",
            //       "otpValue": "<encrypted>",
            //       "mobile": "9876543210"   ← plain text, for ABHA communication
            //     }
            //   },
            //   "consent": { "code": "abha-enrollment", "version": "1.4" }
            // }
            Map<String, Object> otpData = new LinkedHashMap<>();
            otpData.put("txnId",    txnId);
            otpData.put("otpValue", encryptedOtp);
            otpData.put("mobile",   cleanMobile);   // plain text — ABHA communication number

            Map<String, Object> authData = new LinkedHashMap<>();
            authData.put("authMethods", List.of("otp"));
            authData.put("otp",         otpData);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("authData", authData);
            body.put("consent",  Map.of("code", "abha-enrollment", "version", "1.4"));

            log.info("Calling enrolByAadhaar: txnId={}", txnId);
            JsonNode abdmResponse = postWithDebug(config.enrolByAadhaarUrl(), token, body);
            log.info("=== STEP 5: ABHA Created! ===");

            // Extract x-token for next steps
            String xToken = abdmResponse.path("tokens").path("token").asText();

            return ResponseEntity.ok(Map.of(
                    "step",       "5 - Verify OTP / Create ABHA",
                    "status",     "SUCCESS",
                    "xToken",     xToken,
                    "message",    abdmResponse.path("message").asText(),
                    "abhaNumber", abdmResponse.path("ABHAProfile").path("ABHANumber").asText(""),
                    "name",       abdmResponse.path("ABHAProfile").path("firstName").asText(""),
                    "isNew",      abdmResponse.path("isNew").asBoolean(),
                    "nextStep",   "Call GET /api/step/suggestions?txnId=" + txnId + " with header X-token: " + xToken
            ));
        } catch (Exception e) {
            log.error("Step 5 FAILED: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "5 - Verify OTP",
                    "error", e.getMessage(),
                    "hint",  "If ABDM says 'Invalid Mobile Number' — the mobile you provided does not match the one registered with your Aadhaar"
            ));
        }
    }

    // ── STEP 6: ABHA Address Suggestions ─────────────────────────────────────

    @GetMapping("/suggestions")
    @Operation(
            summary     = "Step 6 — Get ABHA address suggestions",
            description = "Params: txnId. Header: X-token (from Step 5 response). " +
                    "Returns list of available ABHA addresses to choose from."
    )
    public ResponseEntity<?> suggestions(@RequestParam String txnId,
                                         @RequestHeader("X-token") String xToken) {
        try {
            String token = authService.getAccessToken();
            String url   = config.enrolSuggestionUrl() + "?txnId=" + txnId;

            Request request = new Request.Builder()
                    .url(url)
                    .header("REQUEST-ID",    UUID.randomUUID().toString())
                    .header("TIMESTAMP",     Instant.now().toString())
                    .header("Authorization", "Bearer " + token)
                    .header("X-token",       "Bearer " + xToken)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                JsonNode json = objectMapper.readTree(body);
                log.info("=== STEP 6: Suggestions OK ===");
                return ResponseEntity.ok(Map.of(
                        "step",        "6 - ABHA Suggestions",
                        "status",      "SUCCESS",
                        "suggestions", json,
                        "nextStep",    "Pick one address and call POST /api/step/create-address"
                ));
            }
        } catch (Exception e) {
            log.error("Step 6 FAILED", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "6 - Suggestions",
                    "error", e.getMessage()
            ));
        }
    }

    // ── STEP 7: Create ABHA Address ───────────────────────────────────────────

    @PostMapping("/create-address")
    @Operation(
            summary     = "Step 7 — Create preferred ABHA address",
            description = "Body: { \"txnId\": \"...\", \"preferredAbhaAddress\": \"yourname@abdm\" }. " +
                    "Header: X-token (from Step 5). Pick address from Step 6 suggestions."
    )
    public ResponseEntity<?> createAddress(@org.springframework.web.bind.annotation.RequestBody Map<String, String> req,
                                           @RequestHeader("X-token") String xToken) {
        try {
            String token   = authService.getAccessToken();
            String txnId   = req.get("txnId");
            String address = req.get("preferredAbhaAddress");

            if (txnId == null || txnId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "txnId is required",
                        "hint",  "Use the same txnId from Step 4/5"));
            }
            if (address == null || address.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "preferredAbhaAddress is required",
                        "hint",  "Pick one address from Step 6 suggestions, e.g. john.doe@abdm"));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("txnId", txnId);
            body.put("preferredAbhaAddress", address);

            String jsonBody = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(config.enrolAbhaAddressUrl())
                    .header("REQUEST-ID",    UUID.randomUUID().toString())
                    .header("TIMESTAMP",     Instant.now().toString())
                    .header("Authorization", "Bearer " + token)
                    .header("X-token",       "Bearer " + xToken)
                    .header("Content-Type",  "application/json")
                    .post(RequestBody.create(jsonBody, JSON_TYPE))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                JsonNode json   = objectMapper.readTree(respBody);
                log.info("=== STEP 7: ABHA Address Created ===");
                return ResponseEntity.ok(Map.of(
                        "step",         "7 - Create ABHA Address",
                        "status",       "SUCCESS",
                        "abhaAddress",  address,
                        "response",     json,
                        "nextStep",     "Call GET /api/step/profile with header X-token"
                ));
            }
        } catch (Exception e) {
            log.error("Step 7 FAILED", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "7 - Create Address",
                    "error", e.getMessage()
            ));
        }
    }

    // ── STEP 8: Get Profile ───────────────────────────────────────────────────

    @GetMapping("/profile")
    @Operation(
            summary     = "Step 8 — Get ABHA Profile",
            description = "Header: X-token (from Step 5). Returns full ABHA profile including ABHA number, name, photo."
    )
    public ResponseEntity<?> profile(@RequestHeader("X-token") String xToken) {
        try {
            String token = authService.getAccessToken();

            Request request = new Request.Builder()
                    .url(config.profileUrl())
                    .header("REQUEST-ID",    UUID.randomUUID().toString())
                    .header("TIMESTAMP",     Instant.now().toString())
                    .header("Authorization", "Bearer " + token)
                    .header("X-token",       "Bearer " + xToken)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                JsonNode json = objectMapper.readTree(body);
                log.info("=== STEP 8: Profile Fetched! M1 COMPLETE ===");
                return ResponseEntity.ok(Map.of(
                        "step",       "8 - Profile",
                        "status",     "SUCCESS",
                        "m1Status",   "COMPLETE — All M1 steps passed!",
                        "profile",    json
                ));
            }
        } catch (Exception e) {
            log.error("Step 8 FAILED", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "8 - Profile",
                    "error", e.getMessage()
            ));
        }
    }

    // ── STEP 9: Login — Request OTP ──────────────────────────────────────────

    @PostMapping("/login-otp")
    @Operation(
            summary     = "Step 9 — Login: Request OTP for existing ABHA",
            description = "Send OTP to an existing ABHA holder for login.\n\n" +
                    "Body: { \"abhaNumber\": \"14-digit-abha-number\" }\n\n" +
                    "ABHA number format: 91-XXXX-XXXX-XXXX (or without dashes: 91XXXXXXXXXXXX)\n\n" +
                    "Returns txnId for Step 10."
    )
    public ResponseEntity<?> loginRequestOtp(
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> req) {
        try {
            String abhaNumber = req.get("abhaNumber");
            if (abhaNumber == null || abhaNumber.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "abhaNumber is required",
                        "hint",  "Use the 14-digit ABHA number from Step 5/8 response"));
            }

            String token             = authService.getAccessToken();
            String encryptedAbha     = encryptionService.encrypt(abhaNumber.replaceAll("-", ""));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("loginId",   encryptedAbha);
            body.put("scope",     "abha-login");
            body.put("loginHint", "abha-number");
            body.put("otpSystem", "abdm");

            log.info("=== STEP 9: Login OTP request for ABHA (last 4): {} ===",
                    abhaNumber.substring(abhaNumber.length() - 4));

            JsonNode abdmResponse = postWithDebug(config.loginOtpUrl(), token, body);
            String txnId = abdmResponse.path("txnId").asText();

            return ResponseEntity.ok(Map.of(
                    "step",     "9 - Login Request OTP",
                    "status",   "SUCCESS",
                    "txnId",    txnId,
                    "message",  abdmResponse.path("message").asText("OTP sent"),
                    "nextStep", "Call POST /api/step/login-verify with { txnId, otp }"
            ));
        } catch (Exception e) {
            log.error("Step 9 FAILED: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "9 - Login Request OTP",
                    "error", e.getMessage()
            ));
        }
    }

    // ── STEP 10: Login — Verify OTP ──────────────────────────────────────────

    @PostMapping("/login-verify")
    @Operation(
            summary     = "Step 10 — Login: Verify OTP and get login token",
            description = "Body: { \"txnId\": \"from-step-9\", \"otp\": \"6-digit-otp\" }\n\n" +
                    "OTP is RSA-encrypted automatically.\n" +
                    "Returns xToken to use in Step 11."
    )
    public ResponseEntity<?> loginVerifyOtp(
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> req) {
        try {
            String txnId = req.get("txnId");
            String otp   = req.get("otp");

            if (txnId == null || txnId.isBlank() || otp == null || otp.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "txnId and otp are required"));
            }

            String token        = authService.getAccessToken();
            String encryptedOtp = encryptionService.encrypt(otp);

            Map<String, Object> otpData = new LinkedHashMap<>();
            otpData.put("txnId",    txnId);
            otpData.put("otpValue", encryptedOtp);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("txnId",     txnId);
            body.put("scope",     "abha-login");
            body.put("loginHint", "abha-number");
            body.put("authData",  Map.of(
                    "authMethods", List.of("otp"),
                    "otp",         otpData
            ));

            log.info("=== STEP 10: Login Verify OTP. txnId={} ===", txnId);
            JsonNode abdmResponse = postWithDebug(config.loginVerifyUrl(), token, body);

            String xToken = abdmResponse.path("tokens").path("token").asText();
            log.info("=== STEP 10: Login successful! ===");

            return ResponseEntity.ok(Map.of(
                    "step",     "10 - Login Verify OTP",
                    "status",   "SUCCESS",
                    "xToken",   xToken,
                    "message",  abdmResponse.path("message").asText("Login successful"),
                    "nextStep", "Call GET /api/step/login-profile with header X-token: " + xToken
            ));
        } catch (Exception e) {
            log.error("Step 10 FAILED: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "10 - Login Verify OTP",
                    "error", e.getMessage()
            ));
        }
    }

    // ── STEP 11: Login — Get Profile ─────────────────────────────────────────

    @GetMapping("/login-profile")
    @Operation(
            summary     = "Step 11 — Login: Get ABHA Profile after login",
            description = "Header: X-token (from Step 10 login response).\n\n" +
                    "Returns full ABHA profile — confirms login flow is complete.\n" +
                    "This completes the M1 Login scenario for NHA demo."
    )
    public ResponseEntity<?> loginProfile(@RequestHeader("X-token") String xToken) {
        try {
            String token = authService.getAccessToken();

            Request request = new Request.Builder()
                    .url(config.profileUrl())
                    .header("REQUEST-ID",    UUID.randomUUID().toString())
                    .header("TIMESTAMP",     Instant.now().toString())
                    .header("Authorization", "Bearer " + token)
                    .header("X-token",       "Bearer " + xToken)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                JsonNode json = objectMapper.readTree(body);
                log.info("=== STEP 11: Login Profile fetched! M1 LOGIN FLOW COMPLETE ===");

                return ResponseEntity.ok(Map.of(
                        "step",       "11 - Login Profile",
                        "status",     "SUCCESS",
                        "m1Status",   "COMPLETE — Enrollment + Login flows both passed!",
                        "abhaNumber", json.path("ABHANumber").asText(""),
                        "name",       json.path("firstName").asText(""),
                        "mobile",     json.path("mobile").asText(""),
                        "profile",    json
                ));
            }
        } catch (Exception e) {
            log.error("Step 11 FAILED: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "step",  "11 - Login Profile",
                    "error", e.getMessage()
            ));
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private JsonNode post(String url, String token, Object body) throws Exception {
        return postWithDebug(url, token, body);
    }

    /** Like post() but logs full request keys and full response body on error */
    private JsonNode postWithDebug(String url, String token, Object body) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);

        // Log request keys (not values — they're encrypted)
        try {
            JsonNode reqNode = objectMapper.readTree(jsonBody);
            log.info("→ POST {} | keys: {}", url, reqNode.fieldNames());
        } catch (Exception ignored) {}

        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type",  "application/json")
                .header("Accept",        "application/json")
                .header("REQUEST-ID",    UUID.randomUUID().toString())
                .header("TIMESTAMP",     Instant.now().toString())
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            log.info("← POST {} [{}]: {}", url, response.code(), respBody);
            if (!response.isSuccessful()) {
                throw new RuntimeException("API failed [" + response.code() + "]: " + respBody);
            }
            return objectMapper.readTree(respBody);
        }
    }
}
