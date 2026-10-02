package com.seatreservation.repository;

import com.seatreservation.entity.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    // Used during reservation/cancellation.
    // Requested seat rows are locked before changing them.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM Seat s
            WHERE s.show.id = :showId
              AND s.seatNumber IN :seatNumbers
            ORDER BY s.seatNumber
            """)
    List<Seat> findSeatsForUpdate(
            @Param("showId") Long showId,
            @Param("seatNumbers") List<Integer> seatNumbers
    );

    // Used by GET /shows/{id}.
    // No write lock is needed because this only reads state.
    @Query("""
            SELECT s
            FROM Seat s
            WHERE s.show.id = :showId
            ORDER BY s.seatNumber
            """)
    List<Seat> findByShowId(
            @Param("showId") Long showId
    );
}