package com.cielo.booking.repository;

import com.cielo.booking.domain.Reservation;
import com.cielo.booking.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findTop100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
            ReservationStatus status,
            OffsetDateTime now
    );

    @Modifying
    @Query("""
        update Reservation r
           set r.status = :newStatus,
               r.updatedAt = :now
         where r.id = :id
           and r.status = :expectedStatus
    """)
    int transitionStatus(
            @Param("id") UUID id,
            @Param("expectedStatus") ReservationStatus expectedStatus,
            @Param("newStatus") ReservationStatus newStatus,
            @Param("now") OffsetDateTime now
    );
}
