package com.cielo.booking.repository;

import com.cielo.booking.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    @Modifying
    @Query("""
        update Event e
           set e.availableQuantity = e.availableQuantity - :quantity
         where e.id = :eventId
           and e.availableQuantity >= :quantity
    """)
    int reserveInventory(@Param("eventId") UUID eventId,
                         @Param("quantity") int quantity);

    @Modifying
    @Query("""
        update Event e
           set e.availableQuantity = e.availableQuantity + :quantity
         where e.id = :eventId
           and e.availableQuantity + :quantity <= e.capacity
    """)
    int releaseInventory(@Param("eventId") UUID eventId,
                         @Param("quantity") int quantity);
}
