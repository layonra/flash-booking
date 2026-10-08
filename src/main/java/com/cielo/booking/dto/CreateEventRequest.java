package com.cielo.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateEventRequest(
        @NotBlank String name,
        @Positive int capacity
) {
}
