package com.cielo.booking.controller;

import com.cielo.booking.dto.ReservationResponse;
import com.cielo.booking.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/reservations")
public class ReservationQueryController {

    private final ReservationService service;

    public ReservationQueryController(ReservationService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    ReservationResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID id) {
        service.cancel(id);
    }
}
