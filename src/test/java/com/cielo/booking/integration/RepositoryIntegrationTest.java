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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired EventRepository events;
    @Autowired ReservationRepository reservations;
    @Autowired IdempotencyKeyRepository keys;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() {
        keys.deleteAll();
        reservations.deleteAll();
        events.deleteAll();
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private void inTx(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    private Event newEvent(int capacity) {
        return events.save(new Event(
                UUID.randomUUID(), "Repo Event " + UUID.randomUUID(), capacity,
                OffsetDateTime.now()));
    }

    private Reservation newReservation(Event event, int quantity,
                                       ReservationStatus status,
                                       OffsetDateTime expiresAt) {
        return reservations.save(new Reservation(
                UUID.randomUUID(), event.getId(), quantity, expiresAt,
                OffsetDateTime.now()));
    }

    @Test
    void reserveInventoryMustDebitOnlyWhenAvailable() {
        Event event = newEvent(10);

        assertEquals(1, inTx(() -> events.reserveInventory(event.getId(), 4)));
        assertEquals(6, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        assertEquals(0, inTx(() -> events.reserveInventory(event.getId(), 7)));

        assertEquals(1, inTx(() -> events.reserveInventory(event.getId(), 6)));
        assertEquals(0, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        assertEquals(0, inTx(() -> events.reserveInventory(event.getId(), 1)));
    }

    @Test
    void releaseInventoryMustCreditUpToCapacity() {
        Event event = newEvent(10);
        inTx(() -> events.reserveInventory(event.getId(), 8));

        assertEquals(1, inTx(() -> events.releaseInventory(event.getId(), 3)));
        assertEquals(5, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        assertEquals(0, inTx(() -> events.releaseInventory(event.getId(), 6)));
        assertEquals(5, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        assertEquals(1, inTx(() -> events.releaseInventory(event.getId(), 5)));
        assertEquals(10, events.findById(event.getId()).orElseThrow().getAvailableQuantity());
    }

    @Test
    void releaseInventoryMustRejectNegativeGuardViolations() {
        Event event = newEvent(10);
        inTx(() -> events.reserveInventory(event.getId(), 5));

        assertEquals(1, inTx(() -> events.releaseInventory(event.getId(), 5)));
        assertEquals(10, events.findById(event.getId()).orElseThrow().getAvailableQuantity());
        assertEquals(0, inTx(() -> events.releaseInventory(event.getId(), 1)));
    }

    @Test
    void transitionStatusMustBeCasOnExpectedStatus() {
        Event event = newEvent(10);
        Reservation reservation = newReservation(event, 2, ReservationStatus.PENDING,
                OffsetDateTime.now().plusMinutes(1));

        assertEquals(1, inTx(() -> reservations.transitionStatus(
                reservation.getId(), ReservationStatus.PENDING,
                ReservationStatus.CANCELLED, OffsetDateTime.now())));

        assertEquals(0, inTx(() -> reservations.transitionStatus(
                reservation.getId(), ReservationStatus.PENDING,
                ReservationStatus.EXPIRED, OffsetDateTime.now())));

        Reservation updated = reservations.findById(reservation.getId()).orElseThrow();
        assertEquals(ReservationStatus.CANCELLED, updated.getStatus());
        assertTrue(updated.getUpdatedAt().isAfter(updated.getCreatedAt()));
    }

    @Test
    void transitionStatusMustIgnoreUnknownReservation() {
        assertEquals(0, inTx(() -> reservations.transitionStatus(
                UUID.randomUUID(), ReservationStatus.PENDING,
                ReservationStatus.EXPIRED, OffsetDateTime.now())));
    }

    @Test
    void expirationCandidateQueryMustReturnOldestPendingOnly() {
        Event event = newEvent(10);
        OffsetDateTime now = OffsetDateTime.now();

        Reservation expiredOld = newReservation(event, 1, ReservationStatus.PENDING,
                now.minusMinutes(3));
        Reservation expiredNew = newReservation(event, 1, ReservationStatus.PENDING,
                now.minusMinutes(1));
        newReservation(event, 1, ReservationStatus.PENDING, now.plusMinutes(5));

        Reservation cancelled = newReservation(event, 1, ReservationStatus.PENDING,
                now.minusMinutes(2));
        inTx(() -> reservations.transitionStatus(cancelled.getId(),
                ReservationStatus.PENDING, ReservationStatus.CANCELLED, now));

        List<Reservation> candidates =
                reservations.findTop100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                        ReservationStatus.PENDING, now);

        assertEquals(List.of(expiredOld.getId(), expiredNew.getId()),
                candidates.stream().map(Reservation::getId).toList());
    }

}
