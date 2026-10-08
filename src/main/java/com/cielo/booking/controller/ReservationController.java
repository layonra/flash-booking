package com.cielo.booking.controller;

import com.cielo.booking.dto.CreateReservationRequest;
import com.cielo.booking.dto.ReservationResponse;
import com.cielo.booking.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/events/{eventId}/reservations")
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID eventId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request) {

        return ResponseEntity.status(201).body(
                service.reserve(eventId, request, idempotencyKey));
    }
}
