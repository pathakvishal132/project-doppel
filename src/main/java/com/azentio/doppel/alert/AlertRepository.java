package com.azentio.doppel.alert;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    @Query("""
            select a from Alert a
            where (:status is null or a.status = :status)
              and (:brandId is null or a.brandId = :brandId)
            """)
    Page<Alert> search(@Param("status") AlertStatus status, @Param("brandId") Long brandId, Pageable pageable);

    long countByRequestId(String requestId);
}
