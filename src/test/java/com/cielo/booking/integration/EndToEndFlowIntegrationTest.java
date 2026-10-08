package com.cielo.booking.integration;

import com.cielo.booking.dto.EventResponse;
import com.cielo.booking.dto.ReservationResponse;
import com.cielo.booking.integration.support.AbstractIntegrationTest;
import com.cielo.booking.repository.EventRepository;
import com.cielo.booking.repository.IdempotencyKeyRepository;
import com.cielo.booking.repository.ReservationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EndToEndFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EventRepository events;
    @Autowired ReservationRepository reservations;
    @Autowired IdempotencyKeyRepository keys;

    @BeforeEach
    void clean() {
        keys.deleteAll();
        reservations.deleteAll();
        events.deleteAll();
    }

    private UUID createEvent(String name, int capacity) throws Exception {
        String body = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "capacity": %d}
                                """.formatted(name, capacity)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(body, EventResponse.class).id();
    }

    @Test
    void eventLifecycleViaHttp() throws Exception {
        String created = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "HTTP Event", "capacity": 5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("HTTP Event"))
                .andExpect(jsonPath("$.capacity").value(5))
                .andExpect(jsonPath("$.availableQuantity").value(5))
                .andReturn().getResponse().getContentAsString();

        UUID eventId = objectMapper.readValue(created, EventResponse.class).id();

        mockMvc.perform(get("/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId.toString()));

        mockMvc.perform(get("/events/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "  ", "capacity": 5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Bad Capacity", "capacity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void reservationLifecycleViaHttp() throws Exception {
        UUID eventId = createEvent("Reservation Event", 10);

        String reservationBody = mockMvc.perform(
                        post("/events/{eventId}/reservations", eventId)
                                .header("Idempotency-Key", "res-key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"quantity": 3}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        UUID reservationId = objectMapper
                .readValue(reservationBody, ReservationResponse.class).id();

        mockMvc.perform(get("/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(7));

        String replayBody = mockMvc.perform(
                        post("/events/{eventId}/reservations", eventId)
                                .header("Idempotency-Key", "res-key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"quantity": 3}
                                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertEquals(reservationId,
                objectMapper.readValue(replayBody, ReservationResponse.class).id());
        assertEquals(1, keys.count());

        mockMvc.perform(post("/events/{eventId}/reservations", eventId)
                        .header("Idempotency-Key", "res-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 5}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(post("/events/{eventId}/reservations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mockMvc.perform(post("/events/{eventId}/reservations", eventId)
                        .header("Idempotency-Key", "res-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/events/{eventId}/reservations", eventId)
                        .header("Idempotency-Key", "res-key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 99}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"));

        mockMvc.perform(get("/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(get("/reservations/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

        mockMvc.perform(delete("/reservations/{id}", reservationId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(10));

        mockMvc.perform(delete("/reservations/{id}", reservationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));
    }

    @Test
    void idempotentReplayAfterCancellationMustReturnCurrentState() throws Exception {
        UUID eventId = createEvent("Replay Event", 10);

        String body = mockMvc.perform(
                        post("/events/{eventId}/reservations", eventId)
                                .header("Idempotency-Key", "replay-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"quantity": 2}
                                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID reservationId = objectMapper
                .readValue(body, ReservationResponse.class).id();

        mockMvc.perform(delete("/reservations/{id}", reservationId))
                .andExpect(status().isNoContent());

        // replay must return the reservation's current state, not a new one
        mockMvc.perform(
                        post("/events/{eventId}/reservations", eventId)
                                .header("Idempotency-Key", "replay-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"quantity": 2}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // replay must not touch inventory: full 10 stays available
        mockMvc.perform(get("/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(10));
    }

    @Test
    void invalidUuidPathParameterMustReturnBadRequest() throws Exception {
        mockMvc.perform(get("/reservations/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        mockMvc.perform(get("/events/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void reservationsMustNotAffectOtherEventsInventory() throws Exception {
        UUID eventA = createEvent("Event A", 10);
        UUID eventB = createEvent("Event B", 10);

        mockMvc.perform(post("/events/{eventId}/reservations", eventA)
                        .header("Idempotency-Key", "iso-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 3}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events/{id}", eventA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(7));

        mockMvc.perform(get("/events/{id}", eventB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(10));
    }

    @Test
    void unknownRouteMustReturn404ErrorContract() throws Exception {
        mockMvc.perform(get("/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found."))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/no-such-route"));
    }
}
