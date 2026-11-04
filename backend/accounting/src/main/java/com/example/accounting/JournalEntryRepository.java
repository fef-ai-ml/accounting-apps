package com.example.accounting;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface JournalEntryRepository extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {

	/** A racing change waits here, then sees the committed row: Postgres re-checks the WHERE, so a deleted entry is empty. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from JournalEntry e where e.id = :id")
	Optional<JournalEntry> lockForChange(@Param("id") long id);

}
