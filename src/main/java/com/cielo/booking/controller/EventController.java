package com.cielo.booking.controller;

import com.cielo.booking.dto.CreateEventRequest;
import com.cielo.booking.dto.EventResponse;
import com.cielo.booking.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<EventResponse> create(
            @Valid @RequestBody CreateEventRequest request) {

        return ResponseEntity.status(201).body(service.create(request));
    }

    @GetMapping("/{id}")
    EventResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
