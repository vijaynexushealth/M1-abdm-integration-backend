package com.nhcx.abdm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhcx.abdm.config.AbdmConfig;
import com.nhcx.abdm.util.HeaderUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M1AbhaService — Milestone 1: ABHA Creation & Verification
 *
 * Enrollment flow (Aadhaar OTP):
 *   Step 1  requestOtpForEnrollment()  → txnId
 *   Step 2  enrolByAadhaar()           → ABHAProfile + x-token
 *   Step 3  (optional) verifyMobileOtp()
 *   Step 4  getAbhaSuggestions()       → address list
 *   Step 5  createAbhaAddress()        → final ABHA address
 *
 * Login flow:
 *   requestLoginOtp()  → txnId
 *   verifyLoginOtp()   → ABHAProfile + x-token
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class M1AbhaService {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final AbdmConfig        config;
    private final AuthService       authService;
    private final EncryptionService encryptionService;
    private final OkHttpClient      httpClient;
    private final ObjectMapper      objectMapper;

    // ── STEP 1: Request OTP ──────────────────────────────────────────────────

    public String requestOtpForEnrollment(String aadhaarNumber) throws Exception {
        String token            = authService.getAccessToken();
        String encryptedAadhaar = encryptionService.encrypt(aadhaarNumber);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txnId",     "");
        body.put("scope",     List.of("abha-enrol"));
        body.put("loginHint", "aadhaar");
        body.put("loginId",   encryptedAadhaar);
        body.put("otpSystem", "aadhaar");

        String resp = post(config.enrolOtpUrl(), token, body);
        log.info("OTP request response: {}", resp);
        return objectMapper.readTree(resp).get("txnId").asText();
    }

    // ── STEP 2: Enrol by Aadhaar OTP ────────────────────────────────────────

    public JsonNode enrolByAadhaar(String txnId, String otp, String mobile) throws Exception {
        String token           = authService.getAccessToken();
        String encryptedOtp    = encryptionService.encrypt(otp);
        String encryptedMobile = encryptionService.encrypt(mobile);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txnId",  txnId);
        body.put("scope",  List.of("abha-enrol"));
        body.put("authData", Map.of(
                "authMethods", List.of("otp"),
                "otp", Map.of("txnId", txnId, "otpValue", encryptedOtp, "mobile", encryptedMobile)
        ));
        body.put("consent", Map.of("code", "abha-enrollment", "version", "1.4"));

        String resp = post(config.enrolByAadhaarUrl(), token, body);
        log.info("Enrol response: {}", resp);
        return objectMapper.readTree(resp);
    }

    // ── STEP 3: Verify mobile OTP (optional) ────────────────────────────────

    public String requestMobileOtp(String txnId, String mobile) throws Exception {
        String token           = authService.getAccessToken();
        String encryptedMobile = encryptionService.encrypt(mobile);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txnId",     txnId);
        body.put("scope",     List.of("mobile-verify"));
        body.put("loginHint", "mobile");
        body.put("loginId",   encryptedMobile);
        body.put("otpSystem", "abdm");

        String resp = post(config.enrolOtpUrl(), token, body);
        return objectMapper.readTree(resp).get("txnId").asText();
    }

    public JsonNode verifyMobileOtp(String txnId, String otp) throws Exception {
        String token        = authService.getAccessToken();
        String encryptedOtp = encryptionService.encrypt(otp);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txnId",    txnId);
        body.put("scope",    List.of("mobile-verify"));
        body.put("authData", Map.of(
                "authMethods", List.of("otp"),
                "otp", Map.of("txnId", txnId, "otpValue", encryptedOtp)
        ));

        String resp = post(config.enrolVerifyAbdmUrl(), token, body);
        return objectMapper.readTree(resp);
    }

    // ── STEP 4: ABHA suggestions ─────────────────────────────────────────────

    public JsonNode getAbhaSuggestions(String txnId, String xToken) throws Exception {
        String token = authService.getAccessToken();
        String url   = config.enrolSuggestionUrl() + "?txnId=" + txnId;

        Request request = HeaderUtil
                .withAuthAndXToken(new Request.Builder().url(url), token, xToken, config.getXCmId())
                .get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw new RuntimeException("Suggestions failed [" + response.code() + "]: " + body);
            return objectMapper.readTree(body);
        }
    }

    // ── STEP 5: Create ABHA address ──────────────────────────────────────────

    public JsonNode createAbhaAddress(String txnId, String preferredAddress, String xToken) throws Exception {
        String token = authService.getAccessToken();
        Map<String, Object> body = Map.of("txnId", txnId, "preferredAbhaAddress", preferredAddress);

        Request request = HeaderUtil
                .withAuthAndXToken(new Request.Builder().url(config.enrolAbhaAddressUrl()), token, xToken, config.getXCmId())
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON)).build();

        try (Response response = httpClient.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw new RuntimeException("Create address failed [" + response.code() + "]: " + resp);
            return objectMapper.readTree(resp);
        }
    }

    // ── LOGIN via Aadhaar OTP ────────────────────────────────────────────────

    public String requestLoginOtp(String aadhaarNumber) throws Exception {
        String token            = authService.getAccessToken();
        String encryptedAadhaar = encryptionService.encrypt(aadhaarNumber);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope",     List.of("abha-login", "aadhaar-verify"));
        body.put("loginHint", "aadhaar");
        body.put("loginId",   encryptedAadhaar);
        body.put("otpSystem", "aadhaar");

        String resp = post(config.loginOtpUrl(), token, body);
        return objectMapper.readTree(resp).get("txnId").asText();
    }

    public JsonNode verifyLoginOtp(String txnId, String otp) throws Exception {
        String token        = authService.getAccessToken();
        String encryptedOtp = encryptionService.encrypt(otp);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txnId",    txnId);
        body.put("scope",    List.of("abha-login", "aadhaar-verify"));
        body.put("authData", Map.of(
                "authMethods", List.of("otp"),
                "otp", Map.of("txnId", txnId, "otpValue", encryptedOtp)
        ));

        String resp = post(config.loginVerifyUrl(), token, body);
        return objectMapper.readTree(resp);
    }

    // ── LOGIN via Mobile OTP ─────────────────────────────────────────────────

    public String requestLoginOtpByMobile(String mobile) throws Exception {
        String token           = authService.getAccessToken();
        String encryptedMobile = encryptionService.encrypt(mobile);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope",     List.of("abha-login"));
        body.put("loginHint", "mobile");
        body.put("loginId",   encryptedMobile);
        body.put("otpSystem", "abdm");

        String resp = post(config.loginOtpUrl(), token, body);
        return objectMapper.readTree(resp).get("txnId").asText();
    }

    public JsonNode verifyLoginOtpMobile(String txnId, String otp) throws Exception {
        String token        = authService.getAccessToken();
        String encryptedOtp = encryptionService.encrypt(otp);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txnId",    txnId);
        body.put("scope",    List.of("abha-login"));
        body.put("authData", Map.of(
                "authMethods", List.of("otp"),
                "otp", Map.of("txnId", txnId, "otpValue", encryptedOtp)
        ));

        String resp = post(config.loginVerifyUrl(), token, body);
        return objectMapper.readTree(resp);
    }

    public JsonNode selectAbhaFromMobileLogin(String txnId, String abhaNumber) throws Exception {
        String token = authService.getAccessToken();
        Map<String, Object> body = Map.of("txnId", txnId, "scope", List.of("abha-login"), "ABHANumber", abhaNumber);
        String resp = post(config.loginVerifyUserUrl(), token, body);
        return objectMapper.readTree(resp);
    }

    // ── PROFILE ──────────────────────────────────────────────────────────────

    public JsonNode getProfile(String accessToken, String xToken) throws Exception {
        Request request = HeaderUtil
                .withAuthAndXToken(new Request.Builder().url(config.profileUrl()), accessToken, xToken, config.getXCmId())
                .get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw new RuntimeException("Profile failed [" + response.code() + "]: " + body);
            return objectMapper.readTree(body);
        }
    }

    public byte[] getAbhaCard(String accessToken, String xToken) throws Exception {
        Request request = HeaderUtil
                .withAuthAndXToken(new Request.Builder().url(config.profileCardUrl()), accessToken, xToken, config.getXCmId())
                .get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("ABHA card failed [" + response.code() + "]");
            return response.body() != null ? response.body().bytes() : new byte[0];
        }
    }

    // ── HTTP helper ──────────────────────────────────────────────────────────

    private String post(String url, String accessToken, Object body) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);
        Request request = HeaderUtil
                .withAuth(new Request.Builder().url(url), accessToken, config.getXCmId())
                .post(RequestBody.create(jsonBody, JSON)).build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("POST {} → [{}] {}", url, response.code(), responseBody);
                throw new RuntimeException("API failed [" + response.code() + "]: " + responseBody);
            }
            return responseBody;
        }
    }
}
