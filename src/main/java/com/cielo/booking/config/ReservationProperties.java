package com.cielo.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "reservation")
public record ReservationProperties(
        Duration expirationSeconds,
        Duration expirationScheduler) {

    public ReservationProperties {
        if (expirationSeconds == null || expirationSeconds.isNegative() || expirationSeconds.isZero()) {
            throw new IllegalStateException("reservation.expiration-seconds must be a positive duration");
        }
        if (expirationScheduler == null || expirationScheduler.isNegative() || expirationScheduler.isZero()) {
            throw new IllegalStateException("reservation.expiration-scheduler must be a positive duration");
        }
    }
}
