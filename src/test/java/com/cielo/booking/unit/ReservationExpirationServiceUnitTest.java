package com.cielo.booking.unit;

import com.cielo.booking.domain.Reservation;
import com.cielo.booking.domain.ReservationStatus;
import com.cielo.booking.repository.ReservationRepository;
import com.cielo.booking.service.ReservationExpirationService;
import com.cielo.booking.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationExpirationServiceUnitTest {

    private ReservationRepository reservations;
    private ReservationService service;
    private ReservationExpirationService expirationService;

    private static Reservation candidate(UUID id) {
        return new Reservation(
                id, UUID.randomUUID(), 1,
                OffsetDateTime.now().minusSeconds(1), OffsetDateTime.now());
    }

    @BeforeEach
    void setup() {
        reservations = mock(ReservationRepository.class);
        service = mock(ReservationService.class);
        expirationService = new ReservationExpirationService(reservations, service);
    }

    @Test
    void failingItemMustNotBlockRemainingCandidates() {
        UUID broken = UUID.randomUUID();
        UUID fine = UUID.randomUUID();

        when(reservations.findTop100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                eq(ReservationStatus.PENDING), any()))
                .thenReturn(List.of(candidate(broken), candidate(fine)));

        doThrow(new IllegalStateException("release failed"))
                .when(service).expire(eq(broken), any());

        assertDoesNotThrow(() -> expirationService.expireReservations());

        verify(service).expire(eq(broken), any());
        verify(service).expire(eq(fine), any());
    }

    @Test
    void emptyBatchMustBeNoOp() {
        when(reservations.findTop100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                eq(ReservationStatus.PENDING), any()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> expirationService.expireReservations());

        verify(service, never()).expire(any(), any());
    }
}
