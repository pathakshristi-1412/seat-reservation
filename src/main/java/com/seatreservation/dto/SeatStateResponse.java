package com.seatreservation.dto;

public class SeatStateResponse {

    private final Integer seatNumber;
    private final String status;

    public SeatStateResponse(Integer seatNumber, String status) {
        this.seatNumber = seatNumber;
        this.status = status;
    }

    public Integer getSeatNumber() {
        return seatNumber;
    }

    public String getStatus() {
        return status;
    }
}