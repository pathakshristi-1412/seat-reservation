package com.seatreservation.controller;

import com.seatreservation.dto.ReserveSeatsRequest;
import com.seatreservation.entity.Reservation;
import com.seatreservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shows")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/{showId}/reserve")
    public ResponseEntity<Reservation> reserveSeats(
            @PathVariable Long showId,
            @Valid @RequestBody ReserveSeatsRequest request,
            @RequestHeader("Authorization") String authorization) {

        String userId = authorization.replace("Bearer ", "");

        Reservation reservation =
                reservationService.reserveSeats(
                        showId,
                        userId,
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(reservation);
    }
}