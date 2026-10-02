package com.seatreservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class ReserveSeatsRequest {

    @NotEmpty(message = "seats cannot be empty")
    private List<@NotBlank String> seats;

    public List<String> getSeats() {
        return seats;
    }
}