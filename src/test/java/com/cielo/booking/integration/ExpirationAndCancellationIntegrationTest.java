package com.cielo.booking.integration;

import com.cielo.booking.domain.Event;
import com.cielo.booking.domain.Reservation;
import com.cielo.booking.domain.ReservationStatus;
import com.cielo.booking.exception.ConflictException;
import com.cielo.booking.exception.NotFoundException;
import com.cielo.booking.integration.support.AbstractIntegrationTest;
import com.cielo.booking.repository.EventRepository;
import com.cielo.booking.repository.IdempotencyKeyRepository;
import com.cielo.booking.repository.ReservationRepository;
import com.cielo.booking.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExpirationAndCancellationIntegrationTest extends AbstractIntegrationTest {

    @Autowired EventRepository events;
    @Autowired ReservationRepository reservations;
    @Autowired IdempotencyKeyRepository keys;
    @Autowired ReservationService service;
    @Autowired TransactionTemplate tx;
    @BeforeEach
    void clean() {
        reservations.deleteAll();
        keys.deleteAll();
        events.deleteAll();
    }

    private Event newEvent(int capacity) {
        return events.save(new Event(
                UUID.randomUUID(), "Event " + UUID.randomUUID(), capacity,
                OffsetDateTime.now()));
    }

    /**
     * Realistic setup: the inventory is debited (as reserve() would) and a
     * matching reservation row exists. expire()/cancel() then release it.
     */
    private Reservation newReserved(Event event, int quantity, OffsetDateTime expiresAt) {
        tx.executeWithoutResult(status ->
                assertEquals(1, events.reserveInventory(event.getId(), quantity)));

        return reservations.save(new Reservation(
                UUID.randomUUID(), event.getId(), quantity, expiresAt,
                OffsetDateTime.now()));
    }

    @Test
    void expirationShouldReturnInventoryExactlyOnce() {
        Event event = newEvent(10);
        Reservation reservation = newReserved(event, 4,
                OffsetDateTime.now().minusSeconds(1));
        assertEquals(6, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        assertTrue(service.expire(reservation.getId(), OffsetDateTime.now()));
        assertFalse(service.expire(reservation.getId(), OffsetDateTime.now()));

        assertEquals(10, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        Reservation updated = reservations.findById(reservation.getId()).orElseThrow();
        assertEquals(ReservationStatus.EXPIRED, updated.getStatus());
    }

    @Test
    void cancelShouldReturnInventoryExactlyOnce() {
        Event event = newEvent(10);
        Reservation reservation = newReserved(event, 4,
                OffsetDateTime.now().plusMinutes(1));
        assertEquals(6, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        service.cancel(reservation.getId());
        assertEquals(10, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        assertThrows(ConflictException.class,
                () -> service.cancel(reservation.getId()));
        assertEquals(10, events.findById(event.getId()).orElseThrow().getAvailableQuantity());
        assertEquals(ReservationStatus.CANCELLED,
                reservations.findById(reservation.getId()).orElseThrow().getStatus());
    }

    @Test
    void cancelMissingReservationMustReturnNotFound() {
        assertThrows(NotFoundException.class,
                () -> service.cancel(UUID.randomUUID()));
    }

    @Test
    void expireMissingReservationMustBeNoOp() {
        assertFalse(service.expire(UUID.randomUUID(), OffsetDateTime.now()));
    }
}
