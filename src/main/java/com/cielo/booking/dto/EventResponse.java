package com.cielo.booking.dto;

import com.cielo.booking.domain.Event;
import java.time.OffsetDateTime;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String name,
        int capacity,
        int availableQuantity,
        OffsetDateTime createdAt
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getCapacity(),
                event.getAvailableQuantity(),
                event.getCreatedAt()
        );
    }
}
