package com.seatreservation.exception;

import com.seatreservation.metrics.ReservationMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ReservationMetrics reservationMetrics;

    public GlobalExceptionHandler(
            ReservationMetrics reservationMetrics) {
        this.reservationMetrics = reservationMetrics;
    }

    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleSeatUnavailable(
            SeatUnavailableException ex) {

        // Prometheus: reservation declined because seat was taken
        reservationMetrics.reservationDeclined("seat_taken");

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "SEAT_UNAVAILABLE",
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(BookingLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleBookingLimitExceeded(
            BookingLimitExceededException ex) {

        // Prometheus: reservation declined because user hit seat limit
        reservationMetrics.reservationDeclined("per_user_limit");

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "BOOKING_LIMIT_EXCEEDED",
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(InvalidReservationRequestException.class)
    public ResponseEntity<Map<String, String>> handleInvalidReservationRequest(
            InvalidReservationRequestException ex) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "INVALID_RESERVATION_REQUEST",
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(ShowNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleShowNotFound(
            ShowNotFoundException ex) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "SHOW_NOT_FOUND",
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleIdempotencyConflict(
            IdempotencyConflictException ex) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "IDEMPOTENCY_CONFLICT",
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(ReservationAccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleReservationAccessDenied(
            ReservationAccessDeniedException ex) {

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "error", "RESERVATION_ACCESS_DENIED",
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthenticationException(
            AuthenticationException ex) {

        Map<String, String> error = new HashMap<>();

        error.put("error", "UNAUTHORIZED");
        error.put("message", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error);
    }
}