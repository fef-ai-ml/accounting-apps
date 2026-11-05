package com.example.accounting;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SettingsRepository extends JpaRepository<Settings, Long> {

	Optional<Settings> findFirstByOrderByIdAsc();

	/** Each tenant has one row, and Hibernate adds the tenant to this query. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Settings s")
	Optional<Settings> lockCurrent();

}
