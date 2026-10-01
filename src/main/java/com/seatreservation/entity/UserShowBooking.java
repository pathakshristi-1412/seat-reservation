package com.seatreservation.entity;

import jakarta.persistence.*;

@Entity
@Table(
    name = "user_show_bookings",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_user_show",
            columnNames = {"user_id", "show_id"}
        )
    }
)
public class UserShowBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @Column(name = "confirmed_seats", nullable = false)
    private Integer confirmedSeats;

    public UserShowBooking() {
    }

    public UserShowBooking(String userId, Show show) {
        this.userId = userId;
        this.show = show;
        this.confirmedSeats = 0;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Show getShow() {
        return show;
    }

    public Integer getConfirmedSeats() {
        return confirmedSeats;
    }

    public void setConfirmedSeats(Integer confirmedSeats) {
        this.confirmedSeats = confirmedSeats;
    }
}