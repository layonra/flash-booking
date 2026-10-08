CREATE TABLE idempotency_keys (
    key_value VARCHAR(255) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    reservation_id UUID NOT NULL REFERENCES reservations(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_idempotency_reservation
    ON idempotency_keys(reservation_id);
