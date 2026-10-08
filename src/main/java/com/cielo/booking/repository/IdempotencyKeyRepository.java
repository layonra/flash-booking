package com.cielo.booking.repository;

import com.cielo.booking.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * Inserts the key with a plain INSERT so a duplicate primary key always
     * fails. JpaRepository.save() must not be used for this: with a
     * caller-assigned id it takes the merge() path (SELECT then UPDATE) and a
     * concurrent, already-committed row is silently accepted instead of
     * raising the constraint violation that reserve() relies on.
     */
    @Modifying
    @Query(value = """
        INSERT INTO idempotency_keys (key_value, request_hash, reservation_id, created_at)
        VALUES (:key, :requestHash, :reservationId, :createdAt)
        """, nativeQuery = true)
    int insertNew(@Param("key") String key,
                  @Param("requestHash") String requestHash,
                  @Param("reservationId") UUID reservationId,
                  @Param("createdAt") OffsetDateTime createdAt);
}
