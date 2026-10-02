package com.seatreservation.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "seats", uniqueConstraints = {
        @UniqueConstraint(name = "uk_show_seat_number", columnNames =  {"show_id", "seat_code"})
})
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seat_code", nullable = false)
    private String seatCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_reservation_id")
    private Reservation currentReservation;

    public Seat() {
    }

    public Seat(String seatCode, Show show) {
        this.seatCode = seatCode;
        this.show = show;
        this.status = SeatStatus.AVAILABLE;
    }

    public Long getId() {
        return id;
    }

    public String getSeatCode() {
        return seatCode;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public Show getShow() {
        return show;
    }

    public void setStatus(SeatStatus status) {
        this.status = status;
    }

    public Reservation getCurrentReservation() {
        return currentReservation;
    }

    public void setCurrentReservation(Reservation currentReservation) {
        this.currentReservation = currentReservation;
    }
}