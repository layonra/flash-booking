package com.cielo.booking.dto;

import jakarta.validation.constraints.Positive;

public record CreateReservationRequest(
        @Positive int quantity
) {
}
