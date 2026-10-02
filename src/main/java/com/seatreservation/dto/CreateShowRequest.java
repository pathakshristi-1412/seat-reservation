package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

import java.util.List;

public class CreateShowRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotEmpty(message = "seats cannot be empty")
    private List<@NotBlank String> seats;

    @NotNull(message = "price_paise is required")
    @Min(value = 0, message = "price_paise cannot be negative")
    @JsonProperty("price_paise")
    private Long pricePaise;

    public String getName() {
        return name;
    }

    public List<String> getSeats() {
        return seats;
    }

    public Long getPricePaise() {
        return pricePaise;
    }
}