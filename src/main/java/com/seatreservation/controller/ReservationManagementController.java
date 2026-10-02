package com.seatreservation.controller;

import com.seatreservation.dto.ReservationResponse;
import com.seatreservation.service.AuthService;
import com.seatreservation.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reservations")
public class ReservationManagementController {

    private final ReservationService reservationService;
    private final AuthService authService;

    public ReservationManagementController(
            ReservationService reservationService,
            AuthService authService) {

        this.reservationService = reservationService;
        this.authService = authService;
    }

    @PostMapping("/{reservationId}/cancel")
    public ResponseEntity<ReservationResponse> cancelReservation(
            @PathVariable Long reservationId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization) {

        String userId =
                authService.extractUserId(authorization);

        ReservationResponse response =
                reservationService.cancelReservation(
                        reservationId,
                        userId);

        return ResponseEntity.ok(response);
    }
}