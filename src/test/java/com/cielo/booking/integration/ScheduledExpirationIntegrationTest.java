package com.cielo.booking.integration;

import com.cielo.booking.domain.Event;
import com.cielo.booking.domain.Reservation;
import com.cielo.booking.domain.ReservationStatus;
import com.cielo.booking.repository.EventRepository;
import com.cielo.booking.repository.IdempotencyKeyRepository;
import com.cielo.booking.repository.ReservationRepository;
import com.cielo.booking.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ScheduledExpirationIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("reservation.expiration-scheduler", () -> "200ms");
    }

    @Autowired EventRepository events;
    @Autowired ReservationRepository reservations;
    @Autowired IdempotencyKeyRepository keys;
    @Autowired ReservationService service;
    @Autowired TransactionTemplate tx;

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> tickerTask;

    @BeforeEach
    void clean() {
        keys.deleteAll();
        reservations.deleteAll();
        events.deleteAll();
    }

    @AfterEach
    void stopTicker() {
        if (tickerTask != null) {
            tickerTask.cancel(true);
            tickerTask = null;
        }
    }

    @Test
    void schedulerMustExpirePendingReservationsAndReturnInventory() {
        Event event = events.save(new Event(
                UUID.randomUUID(), "Scheduled Event", 5, OffsetDateTime.now()));
        tx.executeWithoutResult(status ->
                assertEquals(1, events.reserveInventory(event.getId(), 2)));
        assertEquals(3, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        reservations.save(new Reservation(
                UUID.randomUUID(), event.getId(), 2,
                OffsetDateTime.now().plusNanos(300_000_000), OffsetDateTime.now()));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Reservation reservation = reservations.findAll().get(0);
            assertEquals(ReservationStatus.EXPIRED, reservation.getStatus());
            assertEquals(5, events.findById(event.getId()).orElseThrow().getAvailableQuantity());
        });
    }

    @Test
    void schedulerMustExpireOnlyDueReservations() {
        Event event = events.save(new Event(
                UUID.randomUUID(), "Mixed Event", 10, OffsetDateTime.now()));
        tx.executeWithoutResult(status ->
                assertEquals(1, events.reserveInventory(event.getId(), 3)));

        Reservation due = reservations.save(new Reservation(
                UUID.randomUUID(), event.getId(), 1,
                OffsetDateTime.now().plusNanos(300_000_000), OffsetDateTime.now()));
        Reservation notDue = reservations.save(new Reservation(
                UUID.randomUUID(), event.getId(), 2,
                OffsetDateTime.now().plusMinutes(10), OffsetDateTime.now()));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(ReservationStatus.EXPIRED,
                        reservations.findById(due.getId()).orElseThrow().getStatus()));

        assertEquals(ReservationStatus.PENDING,
                reservations.findById(notDue.getId()).orElseThrow().getStatus());
        assertEquals(8, events.findById(event.getId()).orElseThrow().getAvailableQuantity());
    }

    @Test
    void concurrentSchedulerAndCancelMustReleaseInventoryExactlyOnce() {
        Event event = events.save(new Event(
                UUID.randomUUID(), "Race Event", 10, OffsetDateTime.now()));
        tx.executeWithoutResult(status ->
                assertEquals(1, events.reserveInventory(event.getId(), 4)));

        Reservation reservation = reservations.save(new Reservation(
                UUID.randomUUID(), event.getId(), 4,
                OffsetDateTime.now().plusNanos(400_000_000), OffsetDateTime.now()));
        assertEquals(6, events.findById(event.getId()).orElseThrow().getAvailableQuantity());

        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(200);
                service.cancel(reservation.getId());
            } catch (Exception ignored) {
            }
        });
        canceller.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Reservation updated = reservations.findById(reservation.getId()).orElseThrow();
            assertTrue(updated.getStatus() == ReservationStatus.EXPIRED
                    || updated.getStatus() == ReservationStatus.CANCELLED);
        });

        assertEquals(10, events.findById(event.getId()).orElseThrow().getAvailableQuantity());
    }
}
