package com.seatreservation.controller;

import com.seatreservation.service.AuthService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Profile("dev")
@RestController
@RequestMapping("/dev")
public class DevTokenController {

    private final AuthService authService;

    public DevTokenController(
            AuthService authService) {

        this.authService = authService;
    }

    @GetMapping("/token")
    public ResponseEntity<Map<String, String>> generateToken(
            @RequestParam String userId) {

        String token =
                authService.generateToken(userId);

        return ResponseEntity.ok(
                Map.of(
                        "user_id", userId,
                        "token", token));
    }
}