package com.cielo.booking.integration;

import com.cielo.booking.domain.Event;
import com.cielo.booking.domain.Reservation;
import com.cielo.booking.domain.ReservationStatus;
import com.cielo.booking.dto.CreateReservationRequest;
import com.cielo.booking.exception.ConflictException;
import com.cielo.booking.integration.support.AbstractIntegrationTest;
import com.cielo.booking.repository.EventRepository;
import com.cielo.booking.repository.IdempotencyKeyRepository;
import com.cielo.booking.repository.ReservationRepository;
import com.cielo.booking.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired EventRepository events;
    @Autowired ReservationRepository reservations;
    @Autowired IdempotencyKeyRepository keys;
    @Autowired ReservationService service;

    @BeforeEach
    void clean() {
        keys.deleteAll();
        reservations.deleteAll();
        events.deleteAll();
    }

    @Test
    void mustNeverOversellUnderConcurrency() throws Exception {
        Event event = events.save(new Event(
                UUID.randomUUID(), "Flash Sale", 100, OffsetDateTime.now()));

        int workers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();

                try {
                    service.reserve(
                            event.getId(),
                            new CreateReservationRequest(20),
                            "key-" + UUID.randomUUID());
                    return true;
                } catch (ConflictException ex) {
                    return false;
                }
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        long successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) successes++;
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(5, successes);

        Event finalEvent = events.findById(event.getId()).orElseThrow();
        assertEquals(0, finalEvent.getAvailableQuantity());

        long pending = reservations.findAll().stream()
                .filter(r -> r.getStatus() == ReservationStatus.PENDING)
                .mapToLong(Reservation::getQuantity)
                .sum();

        assertEquals(100, pending);
    }

    @Test
    void concurrentSameIdempotencyKeyMustReturnConsistentState() throws Exception {
        Event event = events.save(new Event(
                UUID.randomUUID(), "Idempotent Sale", 10, OffsetDateTime.now()));

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        String sharedKey = "same-key";

        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();

                try {
                    return service.reserve(
                            event.getId(),
                            new CreateReservationRequest(1),
                            sharedKey).id().toString();
                } catch (ConflictException ex) {
                    return "CONFLICT";
                }
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long created = outcomes.stream().filter(s -> !"CONFLICT".equals(s)).distinct().count();
        assertTrue(created <= 1,
                "at most one reservation may be created for a single idempotency key, got: "
                        + outcomes);

        int available = events.findById(event.getId()).orElseThrow().getAvailableQuantity();
        assertTrue(available == 9 || available == 10,
                "unexpected available quantity: " + available);
    }
}
