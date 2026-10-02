package com.seatreservation.dto;

import java.util.List;

public class ShowStateResponse {

    private final Long id;
    private final String name;
    private final Integer totalSeats;
    private final Integer available;
    private final Integer held;
    private final Integer confirmed;
    private final List<SeatStateResponse> seats;

    public ShowStateResponse(
            Long id,
            String name,
            Integer totalSeats,
            Integer available,
            Integer held,
            Integer confirmed,
            List<SeatStateResponse> seats) {

        this.id = id;
        this.name = name;
        this.totalSeats = totalSeats;
        this.available = available;
        this.held = held;
        this.confirmed = confirmed;
        this.seats = seats;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getTotalSeats() {
        return totalSeats;
    }

    public Integer getAvailable() {
        return available;
    }

    public Integer getHeld() {
        return held;
    }

    public Integer getConfirmed() {
        return confirmed;
    }

    public List<SeatStateResponse> getSeats() {
        return seats;
    }
}