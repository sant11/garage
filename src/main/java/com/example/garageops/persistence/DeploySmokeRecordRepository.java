package com.example.garageops.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Demonstrates the Spring Data repository convention slices will follow. No custom methods —
 * slice-specific finders land with the slices that need them.
 */
public interface DeploySmokeRecordRepository extends JpaRepository<DeploySmokeRecord, Long> {
}
