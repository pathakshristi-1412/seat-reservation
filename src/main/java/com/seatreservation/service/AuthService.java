package com.seatreservation.service;

import com.seatreservation.exception.AuthenticationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class AuthService {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] authSecret;

    public AuthService(
            @Value("${app.auth.secret}") String authSecret) {

        if (authSecret == null || authSecret.isBlank()) {
            throw new IllegalStateException(
                    "app.auth.secret must be configured");
        }

        this.authSecret =
                authSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String extractUserId(String authorizationHeader) {

        if (authorizationHeader == null
                || !authorizationHeader.startsWith(BEARER_PREFIX)) {

            throw new AuthenticationException(
                    "Missing or invalid Authorization header");
        }

        String token =
                authorizationHeader
                        .substring(BEARER_PREFIX.length())
                        .trim();

        if (token.isBlank()) {
            throw new AuthenticationException(
                    "Bearer token cannot be empty");
        }

        /*
         * Token format:
         *
         * base64Url(userId).base64Url(HMAC-SHA256(encodedUserId))
         */
        String[] parts = token.split("\\.", -1);

        if (parts.length != 2
                || parts[0].isBlank()
                || parts[1].isBlank()) {

            throw new AuthenticationException(
                    "Invalid bearer token");
        }

        try {

            String encodedUserId = parts[0];
            String suppliedSignature = parts[1];

            byte[] expectedSignatureBytes =
                    createSignature(encodedUserId);

            byte[] suppliedSignatureBytes =
                    Base64.getUrlDecoder()
                            .decode(suppliedSignature);

            /*
             * Constant-time comparison prevents timing-based
             * signature comparison attacks.
             */
            if (!MessageDigest.isEqual(
                    expectedSignatureBytes,
                    suppliedSignatureBytes)) {

                throw new AuthenticationException(
                        "Invalid bearer token signature");
            }

            String userId =
                    new String(
                            Base64.getUrlDecoder()
                                    .decode(encodedUserId),
                            StandardCharsets.UTF_8);

            if (userId.isBlank()) {
                throw new AuthenticationException(
                        "Invalid user identity");
            }

            return userId;

        } catch (IllegalArgumentException ex) {

            // Invalid Base64
            throw new AuthenticationException(
                    "Invalid bearer token");

        } catch (AuthenticationException ex) {

            throw ex;

        } catch (Exception ex) {

            throw new AuthenticationException(
                    "Unable to validate bearer token");
        }
    }

    public String generateToken(String userId) {

        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(
                    "User ID cannot be empty");
        }

        try {

            String encodedUserId =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    userId.getBytes(
                                            StandardCharsets.UTF_8));

            byte[] signature =
                    createSignature(encodedUserId);

            String encodedSignature =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(signature);

            return encodedUserId
                    + "."
                    + encodedSignature;

        } catch (Exception ex) {

            throw new IllegalStateException(
                    "Unable to generate authentication token",
                    ex);
        }
    }

    private byte[] createSignature(
            String encodedUserId) throws Exception {

        Mac mac =
                Mac.getInstance(HMAC_ALGORITHM);

        SecretKeySpec secretKey =
                new SecretKeySpec(
                        authSecret,
                        HMAC_ALGORITHM);

        mac.init(secretKey);

        return mac.doFinal(
                encodedUserId.getBytes(
                        StandardCharsets.UTF_8));
    }
}