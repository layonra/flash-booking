package com.cielo.booking.service;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ReservationService {

    private final EventRepository events;
    private final ReservationRepository reservations;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final Duration expiration;

    public ReservationService(
            EventRepository events,
            ReservationRepository reservations,
            IdempotencyKeyRepository idempotencyKeys,
            ReservationProperties properties) {

        this.events = events;
        this.reservations = reservations;
        this.idempotencyKeys = idempotencyKeys;
        this.expiration = properties.expirationSeconds();
    }

    @Transactional
    public ReservationResponse reserve(
            UUID eventId,
            CreateReservationRequest request,
            String idempotencyKey) {

        validateIdempotencyKey(idempotencyKey);

        String requestHash = sha256(eventId + ":" + request.quantity());

        var existing = idempotencyKeys.findById(idempotencyKey);
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), requestHash);
        }

        if (!events.existsById(eventId)) {
            throw new NotFoundException("EVENT_NOT_FOUND", "Event not found.");
        }

        int updated = events.reserveInventory(eventId, request.quantity());

        if (updated == 0) {
            throw new ConflictException(
                    "INSUFFICIENT_INVENTORY",
                    "There are not enough tickets available.");
        }

        OffsetDateTime now = OffsetDateTime.now();

        Reservation reservation = new Reservation(
                UUID.randomUUID(),
                eventId,
                request.quantity(),
                now.plus(expiration),
                now
        );

        reservations.save(reservation);

        try {
            idempotencyKeys.insertNew(
                    idempotencyKey,
                    requestHash,
                    reservation.getId(),
                    now
            );
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "IDEMPOTENCY_CONCURRENT_REQUEST",
                    "A concurrent request with this Idempotency-Key is being processed. Retry the request.");
        }

        return ReservationResponse.from(reservation);
    }

    private ReservationResponse resolveExisting(
            IdempotencyKey existing,
            String requestHash) {

        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ConflictException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request.");
        }

        return reservations.findById(existing.getReservationId())
                .map(ReservationResponse::from)
                .orElseThrow(() ->
                        new ConflictException(
                                "IDEMPOTENCY_STATE_INVALID",
                                "Idempotency record points to a missing reservation."));
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ConflictException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required.");
        }

        if (key.length() > 255) {
            throw new ConflictException(
                    "IDEMPOTENCY_KEY_TOO_LONG",
                    "Idempotency-Key must be at most 255 characters.");
        }
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID id) {
        return reservations.findById(id)
                .map(ReservationResponse::from)
                .orElseThrow(() ->
                        new NotFoundException(
                                "RESERVATION_NOT_FOUND",
                                "Reservation not found."));
    }

    @Transactional
    public void cancel(UUID id) {
        ReleaseOutcome outcome = releaseFromPending(
                id, ReservationStatus.CANCELLED, OffsetDateTime.now(),
                "Could not release reservation inventory.");

        if (outcome == ReleaseOutcome.NOT_FOUND) {
            throw new NotFoundException(
                    "RESERVATION_NOT_FOUND",
                    "Reservation not found.");
        }
        if (outcome == ReleaseOutcome.NOT_PENDING) {
            throw new ConflictException(
                    "RESERVATION_NOT_CANCELLABLE",
                    "Reservation is no longer pending.");
        }
    }

    @Transactional
    public boolean expire(UUID id, OffsetDateTime now) {
        return releaseFromPending(
                id, ReservationStatus.EXPIRED, now,
                "Could not release expired reservation inventory.")
                == ReleaseOutcome.RELEASED;
    }

    private enum ReleaseOutcome {
        RELEASED, NOT_FOUND, NOT_PENDING
    }

    /**
     * Shared shape of cancel() and expire(): CAS the reservation out of
     * PENDING into the target status, then release its inventory. The
     * release failure throws so the whole transaction rolls back, including
     * the status change.
     */
    private ReleaseOutcome releaseFromPending(
            UUID id,
            ReservationStatus target,
            OffsetDateTime now,
            String releaseFailureMessage) {

        Reservation reservation = reservations.findById(id).orElse(null);

        if (reservation == null) {
            return ReleaseOutcome.NOT_FOUND;
        }

        if (reservations.transitionStatus(id, ReservationStatus.PENDING, target, now) == 0) {
            return ReleaseOutcome.NOT_PENDING;
        }

        if (events.releaseInventory(
                reservation.getEventId(),
                reservation.getQuantity()) == 0) {

            throw new IllegalStateException(releaseFailureMessage);
        }

        return ReleaseOutcome.RELEASED;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to hash request.", ex);
        }
    }
}
