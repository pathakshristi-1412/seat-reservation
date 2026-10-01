package com.seatreservation.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "shows")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @Column(name = "price_paise", nullable = false)
    private Long pricePaise;

    public Show() {
    }

    public Show(String name, Integer totalSeats, Long pricePaise) {
        this.name = name;
        this.totalSeats = totalSeats;
        this.pricePaise = pricePaise;
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

    public Long getPricePaise() {
        return pricePaise;
    }
}