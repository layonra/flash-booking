package com.cielo.booking.service;

import com.cielo.booking.domain.Event;
import com.cielo.booking.dto.CreateEventRequest;
import com.cielo.booking.dto.EventResponse;
import com.cielo.booking.exception.NotFoundException;
import com.cielo.booking.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class EventService {

    private final EventRepository events;

    public EventService(EventRepository events) {
        this.events = events;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        Event event = new Event(
                UUID.randomUUID(),
                request.name(),
                request.capacity(),
                OffsetDateTime.now()
        );

        return EventResponse.from(events.save(event));
    }

    @Transactional(readOnly = true)
    public EventResponse get(UUID id) {
        return events.findById(id)
                .map(EventResponse::from)
                .orElseThrow(() ->
                        new NotFoundException("EVENT_NOT_FOUND", "Event not found."));
    }
}
