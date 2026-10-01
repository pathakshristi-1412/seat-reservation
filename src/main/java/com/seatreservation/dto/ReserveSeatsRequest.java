package com.seatreservation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class ReserveSeatsRequest {

    @NotEmpty(message = "seats cannot be empty")
    private List<@NotNull Integer> seats;

    public List<Integer> getSeats() {
        return seats;
    }
}