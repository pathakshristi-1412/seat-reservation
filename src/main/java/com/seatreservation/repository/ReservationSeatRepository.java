package com.seatreservation.repository;

import com.seatreservation.entity.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReservationSeatRepository
        extends JpaRepository<ReservationSeat, Long> {

    @Query("""
            SELECT rs
            FROM ReservationSeat rs
            JOIN FETCH rs.seat
            WHERE rs.reservation.id = :reservationId
            ORDER BY rs.seat.seatCode
            """)
    List<ReservationSeat> findByReservationId(
            @Param("reservationId") Long reservationId
    );
}