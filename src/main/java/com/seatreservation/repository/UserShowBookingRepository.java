package com.seatreservation.repository;

import com.seatreservation.entity.UserShowBooking;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserShowBookingRepository
        extends JpaRepository<UserShowBooking, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT usb
            FROM UserShowBooking usb
            WHERE usb.userId = :userId
              AND usb.show.id = :showId
            """)
    Optional<UserShowBooking> findForUpdate(
            @Param("userId") String userId,
            @Param("showId") Long showId
    );

    @Modifying
    @Query(
        value = """
                INSERT INTO user_show_bookings
                    (user_id, show_id, confirmed_seats)
                VALUES
                    (:userId, :showId, 0)
                ON CONFLICT (user_id, show_id) DO NOTHING
                """,
        nativeQuery = true
    )
    int createIfNotExists(
            @Param("userId") String userId,
            @Param("showId") Long showId
    );
}