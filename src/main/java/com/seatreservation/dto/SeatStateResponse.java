package com.seatreservation.dto;

public class SeatStateResponse {

    private final String seat;
    private final String status;

    public SeatStateResponse(String seat, String status) {
        this.seat = seat;
        this.status = status;
    }

    public String getSeat() {
        return seat;
    }

    public String getStatus() {
        return status;
    }
}