package com.seatreservation.service;

import com.seatreservation.exception.AuthenticationException;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final String BEARER_PREFIX = "Bearer ";

    public String extractUserId(String authorizationHeader) {

        if (authorizationHeader == null
                || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new AuthenticationException(
                    "Missing or invalid Authorization header");
        }

        String userId =
                authorizationHeader.substring(BEARER_PREFIX.length()).trim();

        if (userId.isBlank()) {
            throw new AuthenticationException(
                    "Bearer token cannot be empty");
        }

        return userId;
    }
}