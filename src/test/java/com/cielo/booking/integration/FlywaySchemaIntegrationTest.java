package com.cielo.booking.integration;

import com.cielo.booking.domain.Event;
import com.cielo.booking.domain.Reservation;
import com.cielo.booking.domain.ReservationStatus;
import com.cielo.booking.integration.support.AbstractIntegrationTest;
import com.cielo.booking.repository.EventRepository;
import com.cielo.booking.repository.IdempotencyKeyRepository;
import com.cielo.booking.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FlywaySchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired EventRepository events;
    @Autowired ReservationRepository reservations;
    @Autowired IdempotencyKeyRepository keys;

    @BeforeEach
    void clean() {
        keys.deleteAll();
        reservations.deleteAll();
        events.deleteAll();
    }

    @Test
    void eventShouldBePersistedWithFullAvailability() {
        OffsetDateTime created = OffsetDateTime.now();
        Event event = events.save(new Event(
                UUID.randomUUID(), "Integration Event", 10, created));

        assertEquals(10, event.getAvailableQuantity());

        Event reloaded = events.findById(event.getId()).orElseThrow();
        assertEquals("Integration Event", reloaded.getName());
        assertEquals(10, reloaded.getCapacity());
        assertEquals(10, reloaded.getAvailableQuantity());
        assertTrue(reloaded.getCreatedAt().isEqual(created),
                "createdAt must round-trip as the same instant");
    }

    @Test
    void reservationMustPersistAsPendingWithAllFields() {
        Event event = events.save(new Event(
                UUID.randomUUID(), "Status Event", 5, OffsetDateTime.now()));

        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(1);
        Reservation saved = reservations.save(new Reservation(
                UUID.randomUUID(), event.getId(), 2, expiresAt, OffsetDateTime.now()));

        Reservation reloaded = reservations.findById(saved.getId()).orElseThrow();
        assertEquals(event.getId(), reloaded.getEventId());
        assertEquals(2, reloaded.getQuantity());
        assertEquals(ReservationStatus.PENDING, reloaded.getStatus());
        assertTrue(reloaded.getExpiresAt().isEqual(expiresAt));
        assertTrue(reloaded.getCreatedAt().isEqual(reloaded.getUpdatedAt()));
    }

    @Test
    void reservationSchemaMustRejectInvalidQuantity() {
        Event event = events.save(new Event(
                UUID.randomUUID(), "Quantity Event", 5, OffsetDateTime.now()));

        assertThrows(Exception.class, () -> reservations.saveAndFlush(new Reservation(
                UUID.randomUUID(), event.getId(), 0,
                OffsetDateTime.now().plusMinutes(1), OffsetDateTime.now())));
    }
}
