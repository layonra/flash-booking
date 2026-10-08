CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id),
    quantity INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT chk_reservation_status
        CHECK (status IN ('PENDING', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX idx_reservations_expiration
    ON reservations(status, expires_at);

CREATE INDEX idx_reservations_event
    ON reservations(event_id);
