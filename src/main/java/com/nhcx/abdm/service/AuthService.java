package com.nhcx.abdm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhcx.abdm.config.AbdmConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * AuthService — Gets ABDM Gateway access token.
 *
 * Matches this working curl exactly:
 *   curl --location 'https://dev.abdm.gov.in/api/hiecm/gateway/v3/sessions'
 *   --header 'Content-Type: application/json'
 *   --header 'REQUEST-ID: <uuid>'
 *   --header 'TIMESTAMP: <iso>'
 *   --header 'X-CM-ID: sbx'
 *   --data '{ "clientId": "...", "clientSecret": "...", "grantType": "client_credentials" }'
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final AbdmConfig   config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String cachedToken;
    private long   tokenExpiryMs = 0;

    /** Returns valid access token, auto-refreshes 60s before expiry */
    public synchronized String getAccessToken() throws Exception {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiryMs) {
            return cachedToken;
        }
        return fetchNewToken();
    }

    private String fetchNewToken() throws Exception {
        String url = config.sessionUrl();
        log.info("Fetching ABDM token → {}", url);
        log.info("Client ID: {}", config.getClientId());

        String requestId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();

        String bodyJson = objectMapper.writeValueAsString(Map.of(
                "clientId",     config.getClientId(),
                "clientSecret", config.getClientSecret(),
                "grantType",    "client_credentials"
        ));

        log.debug("Session request body: {}", bodyJson.replace(config.getClientSecret(), "***"));

        // Matches curl headers exactly
        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("REQUEST-ID",   requestId)
                .header("TIMESTAMP",    timestamp)
                .header("X-CM-ID",      config.getXCmId())
                .post(RequestBody.create(bodyJson, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            log.info("Session API [{}]: {}", response.code(),
                    responseBody.length() > 100 ? responseBody.substring(0, 100) + "..." : responseBody);

            if (!response.isSuccessful()) {
                log.error("Auth FAILED [{}] → {}", response.code(), responseBody);
                throw new RuntimeException("ABDM auth failed [" + response.code() + "]: " + responseBody);
            }

            JsonNode json    = objectMapper.readTree(responseBody);
            cachedToken      = json.get("accessToken").asText();
            long expiresIn   = json.path("expiresIn").asLong(1200);
            tokenExpiryMs    = System.currentTimeMillis() + (expiresIn - 60) * 1000L;

            log.info("ABDM token obtained. Expires in {}s", expiresIn);
            return cachedToken;
        }
    }

    /**
     * Register bridge/callback URL with ABDM (one-time sandbox setup).
     *
     * curl --location --request PATCH 'https://dev.abdm.gov.in/devservice/v1/bridges'
     *   --header 'Authorization: Bearer <token>'
     *   --data '{ "url": "<your-ngrok-url>" }'
     */
    public String registerBridgeUrl(String callbackUrl) throws Exception {
        String accessToken = getAccessToken();
        log.info("Registering bridge URL: {}", callbackUrl);

        String bodyJson = objectMapper.writeValueAsString(Map.of("url", callbackUrl));

        Request request = new Request.Builder()
                .url(config.bridgeRegisterUrl())
                .header("Content-Type",  "application/json")
                .header("REQUEST-ID",    UUID.randomUUID().toString())
                .header("TIMESTAMP",     Instant.now().toString())
                .header("X-CM-ID",       config.getXCmId())
                .header("Authorization", "Bearer " + accessToken)
                .method("PATCH", RequestBody.create(bodyJson, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            log.info("Bridge registration [{}]: {}", response.code(), responseBody);
            return responseBody;
        }
    }
}
