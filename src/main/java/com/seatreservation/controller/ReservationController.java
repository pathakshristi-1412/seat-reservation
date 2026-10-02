package com.seatreservation.controller;

import com.seatreservation.dto.ReservationResponse;
import com.seatreservation.dto.ReserveSeatsRequest;
import com.seatreservation.service.AuthService;
import com.seatreservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shows")
public class ReservationController {

    private final ReservationService reservationService;
    private final AuthService authService;

    public ReservationController(
            ReservationService reservationService,
            AuthService authService) {

        this.reservationService = reservationService;
        this.authService = authService;
    }

    @PostMapping("/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserveSeats(
            @PathVariable Long showId,
            @Valid @RequestBody ReserveSeatsRequest request,
            @RequestHeader(value = "Authorization", required = false)
            String authorization,
            @RequestHeader("Idempotency-Key")
            String idempotencyKey) {

        String userId =
                authService.extractUserId(authorization);

        ReservationResponse reservation =
                reservationService.reserveSeats(
                        showId,
                        userId,
                        idempotencyKey,
                        request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(reservation);
    }
}