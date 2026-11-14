package com.azentio.doppel.screening;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreeningRepository extends JpaRepository<ScreeningRecord, Long> {

    Optional<ScreeningRecord> findByRequestId(String requestId);
}
