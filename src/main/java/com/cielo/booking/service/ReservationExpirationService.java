package com.cielo.booking.service;

import com.cielo.booking.domain.ReservationStatus;
import com.cielo.booking.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class ReservationExpirationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationExpirationService.class);

    private final ReservationRepository reservations;
    private final ReservationService reservationService;

    public ReservationExpirationService(
            ReservationRepository reservations,
            ReservationService reservationService) {
        this.reservations = reservations;
        this.reservationService = reservationService;
    }

    @Scheduled(fixedDelayString = "${reservation.expiration-scheduler}")
    public void expireReservations() {
        var candidates =
                reservations.findTop100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                        ReservationStatus.PENDING,
                        OffsetDateTime.now());

        for (var reservation : candidates) {
            try {
                reservationService.expire(
                        reservation.getId(),
                        OffsetDateTime.now());
            } catch (Exception ex) {
                LOG.error("Failed to expire reservation {}", reservation.getId(), ex);
            }
        }
    }
}
