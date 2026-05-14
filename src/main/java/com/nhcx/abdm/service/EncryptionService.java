package com.nhcx.abdm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhcx.abdm.config.AbdmConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * EncryptionService
 *
 * Algorithm : RSA/ECB/OAEPWithSHA-1AndMGF1Padding   (ABDM mandatory)
 * Public key: GET /v3/profile/public/certificate      (requires Bearer token)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EncryptionService {

    private static final String ALGORITHM = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding";

    private final AbdmConfig   config;
    private final AuthService  authService;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String    cachedPublicKeyString;   // raw base64 — returned to caller for display
    private PublicKey cachedPublicKey;

    // ── Public API ───────────────────────────────────────────────────────────

    /** Encrypts plaintext (Aadhaar / Mobile / OTP) using ABDM public key */
    public String encrypt(String plaintext) throws Exception {
        PublicKey publicKey = getPublicKey();
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        String result = Base64.getEncoder().encodeToString(encrypted);
        log.info("Encrypted (first 30): {}...", result.substring(0, Math.min(30, result.length())));
        return result;
    }

    /**
     * Returns the raw Base64 public key string from ABDM.
     * Useful for displaying in the /cert endpoint response.
     */
    public String fetchPublicKeyString() throws Exception {
        getPublicKey();   // ensures cachedPublicKeyString is populated
        return cachedPublicKeyString;
    }

    /** Clears cache — call if ABDM rotates its public key */
    public void clearKeyCache() {
        cachedPublicKey       = null;
        cachedPublicKeyString = null;
        log.info("Public key cache cleared.");
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private PublicKey getPublicKey() throws Exception {
        if (cachedPublicKey != null) {
            log.debug("Using cached ABDM public key");
            return cachedPublicKey;
        }

        String accessToken = authService.getAccessToken();

        log.info("Calling Cert API: {}", config.publicCertUrl());

        // Matches the working curl exactly:
        // curl --location 'https://abhasbx.abdm.gov.in/abha/api/v3/profile/public/certificate'
        // --header 'REQUEST-ID: ...'
        // --header 'TIMESTAMP: ...'
        // --header 'Authorization: Bearer <token>'
        Request request = new Request.Builder()
                .url(config.publicCertUrl())
                .header("REQUEST-ID",    UUID.randomUUID().toString())
                .header("TIMESTAMP",     Instant.now().toString())
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept",        "application/json")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            log.info("Cert API [{}]: {}",
                    response.code(),
                    body.length() > 150 ? body.substring(0, 150) + "..." : body);

            if (!response.isSuccessful()) {
                throw new RuntimeException("Cert API failed [" + response.code() + "]: " + body);
            }

            cachedPublicKeyString = parseKeyString(body);
            cachedPublicKey       = buildPublicKey(cachedPublicKeyString);
            log.info("ABDM public key cached.");
            return cachedPublicKey;
        }
    }

    /**
     * Parses cert response — handles both formats ABDM may return:
     *   JSON : { "publicKey": "MIIBIjAN..." }
     *   Plain: -----BEGIN PUBLIC KEY-----\nMIIBIjAN...\n-----END PUBLIC KEY-----
     */
    private String parseKeyString(String body) {
        if (body.trim().startsWith("{")) {
            try {
                JsonNode json = objectMapper.readTree(body);
                if (json.has("publicKey"))  return json.get("publicKey").asText();
                if (json.has("public_key")) return json.get("public_key").asText();
            } catch (Exception e) {
                log.warn("Failed to parse cert JSON, treating as plain text");
            }
        }
        return body;
    }

    private PublicKey buildPublicKey(String key) throws Exception {
        String cleaned = key
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
    }
}
