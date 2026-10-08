package com.cielo.booking.unit;

import com.cielo.booking.config.ReservationProperties;
import com.cielo.booking.domain.IdempotencyKey;
import com.cielo.booking.domain.Reservation;
import com.cielo.booking.domain.ReservationStatus;
import com.cielo.booking.dto.CreateReservationRequest;
import com.cielo.booking.dto.ReservationResponse;
import com.cielo.booking.exception.ConflictException;
import com.cielo.booking.exception.NotFoundException;
import com.cielo.booking.repository.EventRepository;
import com.cielo.booking.repository.IdempotencyKeyRepository;
import com.cielo.booking.repository.ReservationRepository;
import com.cielo.booking.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReservationServiceUnitTest {

    private static final long EXPIRATION_SECONDS = 300;

    private EventRepository events;
    private ReservationRepository reservations;
    private IdempotencyKeyRepository keys;
    private ReservationService service;

    @BeforeEach
    void setup() {
        events = mock(EventRepository.class);
        reservations = mock(ReservationRepository.class);
        keys = mock(IdempotencyKeyRepository.class);

        service = new ReservationService(events, reservations, keys,
                new ReservationProperties(Duration.ofSeconds(EXPIRATION_SECONDS), Duration.ofSeconds(1)));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Reservation pendingReservation(UUID eventId, int quantity) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Reservation(UUID.randomUUID(), eventId, quantity,
                now.plusSeconds(EXPIRATION_SECONDS), now);
    }

    // ---- reserve ----

    @Test
    void missingIdempotencyKeyMustBeRejected() {
        assertThrows(ConflictException.class,
                () -> service.reserve(UUID.randomUUID(), new CreateReservationRequest(1), null));
        assertThrows(ConflictException.class,
                () -> service.reserve(UUID.randomUUID(), new CreateReservationRequest(1), "   "));

        verifyNoInteractions(events, reservations, keys);
    }

    @Test
    void oversizedIdempotencyKeyMustBeRejected() {
        String longKey = "k".repeat(256);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.reserve(UUID.randomUUID(), new CreateReservationRequest(1), longKey));

        assertEquals("IDEMPOTENCY_KEY_TOO_LONG", ex.getCode());
        verifyNoInteractions(events, reservations, keys);
    }

    @Test
    void missingEventMustReturnNotFound() {
        UUID eventId = UUID.randomUUID();
        when(keys.findById("key")).thenReturn(Optional.empty());
        when(events.existsById(eventId)).thenReturn(false);

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> service.reserve(eventId, new CreateReservationRequest(1), "key"));

        assertEquals("EVENT_NOT_FOUND", ex.getCode());
        verifyNoInteractions(reservations);
        verify(events, never()).reserveInventory(any(), anyInt());
    }

    @Test
    void insufficientInventoryMustConflict() {
        UUID eventId = UUID.randomUUID();
        when(keys.findById("key")).thenReturn(Optional.empty());
        when(events.existsById(eventId)).thenReturn(true);
        when(events.reserveInventory(eventId, 2)).thenReturn(0);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.reserve(eventId, new CreateReservationRequest(2), "key"));

        assertEquals("INSUFFICIENT_INVENTORY", ex.getCode());
        verify(reservations, never()).save(any());
        verify(keys, never()).insertNew(any(), any(), any(), any());
    }

    @Test
    void reserveMustPersistPendingReservationAndIdempotencyKey() {
        UUID eventId = UUID.randomUUID();
        when(keys.findById("key")).thenReturn(Optional.empty());
        when(events.existsById(eventId)).thenReturn(true);
        when(events.reserveInventory(eventId, 2)).thenReturn(1);
        when(keys.insertNew(any(), any(), any(), any())).thenReturn(1);

        OffsetDateTime before = OffsetDateTime.now();
        ReservationResponse response =
                service.reserve(eventId, new CreateReservationRequest(2), "key");

        assertEquals(eventId, response.eventId());
        assertEquals(2, response.quantity());
        assertEquals(ReservationStatus.PENDING, response.status());

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(captor.capture());
        Reservation saved = captor.getValue();

        assertEquals(eventId, saved.getEventId());
        assertEquals(2, saved.getQuantity());
        assertEquals(ReservationStatus.PENDING, saved.getStatus());

        OffsetDateTime expectedExpiry = before.plusSeconds(EXPIRATION_SECONDS);
        assertTrue(saved.getExpiresAt().isAfter(expectedExpiry.minusSeconds(5)));
        assertTrue(saved.getExpiresAt().isBefore(expectedExpiry.plusSeconds(5)));

        verify(keys).insertNew(eq("key"),
                eq(sha256(eventId + ":2")),
                eq(saved.getId()),
                any());
    }

    @Test
    void sameKeyWithDifferentPayloadMustConflict() {
        UUID eventId = UUID.randomUUID();
        when(keys.findById("key")).thenReturn(Optional.of(new IdempotencyKey(
                "key",
                sha256(eventId + ":99"),
                UUID.randomUUID(),
                OffsetDateTime.now())));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.reserve(eventId, new CreateReservationRequest(2), "key"));

        assertEquals("IDEMPOTENCY_KEY_REUSED", ex.getCode());
        verifyNoInteractions(events);
    }

    @Test
    void sameKeyWithSamePayloadMustReplayExistingReservation() {
        UUID eventId = UUID.randomUUID();
        Reservation existing = pendingReservation(eventId, 2);
        when(keys.findById("key")).thenReturn(Optional.of(new IdempotencyKey(
                "key",
                sha256(eventId + ":2"),
                existing.getId(),
                OffsetDateTime.now())));
        when(reservations.findById(existing.getId())).thenReturn(Optional.of(existing));

        ReservationResponse response =
                service.reserve(eventId, new CreateReservationRequest(2), "key");

        assertEquals(existing.getId(), response.id());
        assertEquals(ReservationStatus.PENDING, response.status());

        verify(events, never()).reserveInventory(any(), anyInt());
        verify(reservations, never()).save(any());
    }

    @Test
    void idempotencyRecordPointingToMissingReservationMustConflict() {
        UUID eventId = UUID.randomUUID();
        UUID missingReservationId = UUID.randomUUID();
        when(keys.findById("key")).thenReturn(Optional.of(new IdempotencyKey(
                "key",
                sha256(eventId + ":2"),
                missingReservationId,
                OffsetDateTime.now())));
        when(reservations.findById(missingReservationId)).thenReturn(Optional.empty());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.reserve(eventId, new CreateReservationRequest(2), "key"));

        assertEquals("IDEMPOTENCY_STATE_INVALID", ex.getCode());
    }

    @Test
    void concurrentKeyInsertionMustAskCallerToRetry() {
        UUID eventId = UUID.randomUUID();
        when(keys.findById("key")).thenReturn(Optional.empty());
        when(events.existsById(eventId)).thenReturn(true);
        when(events.reserveInventory(eventId, 2)).thenReturn(1);
        when(keys.insertNew(any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.reserve(eventId, new CreateReservationRequest(2), "key"));

        assertEquals("IDEMPOTENCY_CONCURRENT_REQUEST", ex.getCode());
    }

    // ---- get ----

    @Test
    void getMissingReservationMustReturnNotFound() {
        when(reservations.findById(any())).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> service.get(UUID.randomUUID()));

        assertEquals("RESERVATION_NOT_FOUND", ex.getCode());
    }

    // ---- cancel ----

    @Test
    void cancelMissingReservationMustReturnNotFound() {
        when(reservations.findById(any())).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> service.cancel(UUID.randomUUID()));

        assertEquals("RESERVATION_NOT_FOUND", ex.getCode());
    }

    @Test
    void cancelNonPendingReservationMustConflict() {
        UUID eventId = UUID.randomUUID();
        Reservation reservation = pendingReservation(eventId, 2);
        when(reservations.findById(reservation.getId()))
                .thenReturn(Optional.of(reservation));
        when(reservations.transitionStatus(eq(reservation.getId()),
                eq(ReservationStatus.PENDING), eq(ReservationStatus.CANCELLED), any()))
                .thenReturn(0);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.cancel(reservation.getId()));

        assertEquals("RESERVATION_NOT_CANCELLABLE", ex.getCode());
        verify(events, never()).releaseInventory(any(), anyInt());
    }

    @Test
    void cancelMustReleaseInventory() {
        UUID eventId = UUID.randomUUID();
        Reservation reservation = pendingReservation(eventId, 2);
        when(reservations.findById(reservation.getId()))
                .thenReturn(Optional.of(reservation));
        when(reservations.transitionStatus(eq(reservation.getId()),
                eq(ReservationStatus.PENDING), eq(ReservationStatus.CANCELLED), any()))
                .thenReturn(1);
        when(events.releaseInventory(eventId, 2)).thenReturn(1);

        service.cancel(reservation.getId());

        verify(events).releaseInventory(eventId, 2);
    }

    @Test
    void cancelMustFailWhenInventoryReleaseFails() {
        UUID eventId = UUID.randomUUID();
        Reservation reservation = pendingReservation(eventId, 2);
        when(reservations.findById(reservation.getId()))
                .thenReturn(Optional.of(reservation));
        when(reservations.transitionStatus(eq(reservation.getId()),
                eq(ReservationStatus.PENDING), eq(ReservationStatus.CANCELLED), any()))
                .thenReturn(1);
        when(events.releaseInventory(eventId, 2)).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.cancel(reservation.getId()));
    }

    // ---- expire ----

    @Test
    void expireMissingReservationMustReturnFalse() {
        when(reservations.findById(any())).thenReturn(Optional.empty());

        assertFalse(service.expire(UUID.randomUUID(), OffsetDateTime.now()));
    }

    @Test
    void expireAlreadyTransitionedReservationMustReturnFalse() {
        UUID eventId = UUID.randomUUID();
        Reservation reservation = pendingReservation(eventId, 2);
        when(reservations.findById(reservation.getId()))
                .thenReturn(Optional.of(reservation));
        when(reservations.transitionStatus(eq(reservation.getId()),
                eq(ReservationStatus.PENDING), eq(ReservationStatus.EXPIRED), any()))
                .thenReturn(0);

        assertFalse(service.expire(reservation.getId(), OffsetDateTime.now()));
        verify(events, never()).releaseInventory(any(), anyInt());
    }

    @Test
    void expireMustReleaseInventoryExactlyOnce() {
        UUID eventId = UUID.randomUUID();
        Reservation reservation = pendingReservation(eventId, 3);
        when(reservations.findById(reservation.getId()))
                .thenReturn(Optional.of(reservation));
        when(reservations.transitionStatus(eq(reservation.getId()),
                eq(ReservationStatus.PENDING), eq(ReservationStatus.EXPIRED), any()))
                .thenReturn(1);
        when(events.releaseInventory(eventId, 3)).thenReturn(1);

        assertTrue(service.expire(reservation.getId(), OffsetDateTime.now()));

        verify(events).releaseInventory(eventId, 3);
    }

    @Test
    void expireMustFailWhenInventoryReleaseFails() {
        UUID eventId = UUID.randomUUID();
        Reservation reservation = pendingReservation(eventId, 3);
        when(reservations.findById(reservation.getId()))
                .thenReturn(Optional.of(reservation));
        when(reservations.transitionStatus(eq(reservation.getId()),
                eq(ReservationStatus.PENDING), eq(ReservationStatus.EXPIRED), any()))
                .thenReturn(1);
        when(events.releaseInventory(eventId, 3)).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.expire(reservation.getId(), OffsetDateTime.now()));
    }
}
