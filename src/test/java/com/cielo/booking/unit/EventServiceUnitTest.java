package com.cielo.booking.unit;

import com.cielo.booking.domain.Event;
import com.cielo.booking.dto.CreateEventRequest;
import com.cielo.booking.dto.EventResponse;
import com.cielo.booking.exception.NotFoundException;
import com.cielo.booking.repository.EventRepository;
import com.cielo.booking.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventServiceUnitTest {

    private EventRepository events;
    private EventService service;

    @BeforeEach
    void setup() {
        events = mock(EventRepository.class);
        service = new EventService(events);
    }

    @Test
    void createMustPersistEventWithFullCapacityAvailable() {
        when(events.save(any(Event.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EventResponse response =
                service.create(new CreateEventRequest("Flash Sale", 100));

        assertEquals("Flash Sale", response.name());
        assertEquals(100, response.capacity());
        assertEquals(100, response.availableQuantity());

        var captor = org.mockito.ArgumentCaptor.forClass(Event.class);
        verify(events).save(captor.capture());
        assertNotNull(captor.getValue().getId());
        assertNotNull(captor.getValue().getCreatedAt());
    }

    @Test
    void getExistingEventMustReturnResponse() {
        UUID id = UUID.randomUUID();
        when(events.findById(id)).thenReturn(Optional.of(
                new Event(id, "Event", 10, OffsetDateTime.now())));

        EventResponse response = service.get(id);

        assertEquals(id, response.id());
        assertEquals(10, response.availableQuantity());
    }

    @Test
    void getMissingEventMustReturnNotFound() {
        when(events.findById(any())).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> service.get(UUID.randomUUID()));

        assertEquals("EVENT_NOT_FOUND", ex.getCode());
    }
}
