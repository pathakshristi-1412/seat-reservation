package com.seatreservation.repository;

import com.seatreservation.entity.IdempotencyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecord, Long> {

    @Modifying
    @Query(
        value = """
                INSERT INTO idempotency_records
                    (user_id, idempotency_key, request_hash)
                VALUES
                    (:userId, :idempotencyKey, :requestHash)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                """,
        nativeQuery = true
    )
    int createIfNotExists(
            @Param("userId") String userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT ir
            FROM IdempotencyRecord ir
            WHERE ir.userId = :userId
              AND ir.idempotencyKey = :idempotencyKey
            """)
    Optional<IdempotencyRecord> findForUpdate(
            @Param("userId") String userId,
            @Param("idempotencyKey") String idempotencyKey
    );
}