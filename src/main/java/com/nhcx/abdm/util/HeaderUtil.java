package com.nhcx.abdm.util;

import okhttp3.Request;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds standard ABDM HTTP headers for every API call.
 * Required: REQUEST-ID, TIMESTAMP, X-CM-ID, Authorization
 */
public final class HeaderUtil {

    private HeaderUtil() {}

    public static Request.Builder withAuth(Request.Builder builder, String accessToken, String xCmId) {
        return builder
                .header("Content-Type",  "application/json")
                .header("Accept",        "application/json")
                .header("REQUEST-ID",    UUID.randomUUID().toString())
                .header("TIMESTAMP",     Instant.now().toString())
                .header("X-CM-ID",       xCmId)
                .header("Authorization", "Bearer " + accessToken);
    }

    public static Request.Builder withoutAuth(Request.Builder builder) {
        return builder
                .header("Content-Type", "application/json")
                .header("Accept",       "application/json")
                .header("REQUEST-ID",   UUID.randomUUID().toString())
                .header("TIMESTAMP",    Instant.now().toString());
    }

    /** Add X-token on top of auth headers (needed for profile, ABHA card, QR) */
    public static Request.Builder withAuthAndXToken(Request.Builder builder,
                                                    String accessToken,
                                                    String xToken,
                                                    String xCmId) {
        return withAuth(builder, accessToken, xCmId)
                .header("X-token", "Bearer " + xToken);
    }

    /** Add X-HIP-ID header (required on M2 HIP calls) */
    public static Request.Builder withHipId(Request.Builder builder,
                                            String accessToken,
                                            String xCmId,
                                            String hipId) {
        return withAuth(builder, accessToken, xCmId)
                .header("X-HIP-ID", hipId);
    }
}
