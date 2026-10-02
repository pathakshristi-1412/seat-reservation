package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class ReservationResponse {

    @JsonProperty("reservation_id")
    private final Long reservationId;

    @JsonProperty("show_id")
    private final Long showId;

    @JsonProperty("user_id")
    private final String userId;

    private final List<String> seats;

    @JsonProperty("amount_paise")
    private final Long amountPaise;

    private final String status;

    public ReservationResponse(
            Long reservationId,
            Long showId,
            String userId,
            List<String> seats,
            Long amountPaise,
            String status) {

        this.reservationId = reservationId;
        this.showId = showId;
        this.userId = userId;
        this.seats = seats;
        this.amountPaise = amountPaise;
        this.status = status;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public Long getShowId() {
        return showId;
    }

    public String getUserId() {
        return userId;
    }

    public List<String> getSeats() {
        return seats;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public String getStatus() {
        return status;
    }
}