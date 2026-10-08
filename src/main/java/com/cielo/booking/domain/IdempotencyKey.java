package com.cielo.booking.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {
    @Id
    @Column(name = "key_value")
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyKey() {
    }

    public IdempotencyKey(String key, String requestHash, UUID reservationId,
                          OffsetDateTime createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.reservationId = reservationId;
        this.createdAt = createdAt;
    }

    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public UUID getReservationId() { return reservationId; }
}
