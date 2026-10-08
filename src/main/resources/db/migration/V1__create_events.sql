CREATE TABLE events (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    capacity INTEGER NOT NULL,
    available_quantity INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_event_capacity CHECK (capacity > 0),
    CONSTRAINT chk_event_available_quantity
        CHECK (available_quantity >= 0 AND available_quantity <= capacity)
);
