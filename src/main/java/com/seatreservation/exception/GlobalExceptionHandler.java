package com.seatreservation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleSeatUnavailable(
            SeatUnavailableException ex) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "SEAT_UNAVAILABLE",
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(BookingLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleBookingLimitExceeded(
            BookingLimitExceededException ex) {

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
}