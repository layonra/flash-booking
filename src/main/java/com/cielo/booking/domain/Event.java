package com.cielo.booking.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Event() {
    }

    public Event(UUID id, String name, int capacity, OffsetDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.capacity = capacity;
        this.availableQuantity = capacity;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public int getCapacity() { return capacity; }
    public int getAvailableQuantity() { return availableQuantity; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
