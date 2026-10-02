package com.seatreservation.controller;

import com.seatreservation.entity.Reservation;
import com.seatreservation.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reservations")
public class ReservationManagementController {

    private final ReservationService reservationService;

    public ReservationManagementController(
            ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/{reservationId}/cancel")
    public ResponseEntity<Reservation> cancelReservation(
            @PathVariable Long reservationId,
            @RequestHeader("Authorization") String authorization) {

        String userId = authorization.replace("Bearer ", "");

        Reservation reservation =
                reservationService.cancelReservation(
                        reservationId,
                        userId);

        return ResponseEntity.ok(reservation);
    }
}