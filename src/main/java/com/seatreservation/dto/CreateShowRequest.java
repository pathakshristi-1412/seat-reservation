package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateShowRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotNull(message = "seats is required")
    @Min(value = 1, message = "seats must be at least 1")
    private Integer seats;

    @NotNull(message = "price_paise is required")
    @Min(value = 0, message = "price_paise cannot be negative")
    @JsonProperty("price_paise")
    private Long pricePaise;

    public String getName() {
        return name;
    }

    public Integer getSeats() {
        return seats;
    }

    public Long getPricePaise() {
        return pricePaise;
    }
}