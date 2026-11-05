package com.example.accounting;

import java.time.LocalDate;
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

	/** A reversed close is REVERSED and its reversal has reversalOf set, so neither matches. */
	@Query("""
			select e from JournalEntry e where e.closingYear = :year
			and e.status = com.example.accounting.EntryStatus.POSTED and e.reversalOf is null
			""")
	Optional<JournalEntry> findActiveClose(@Param("year") int year);

	@Query("""
			select count(e) from JournalEntry e where e.closingYear > :year
			and e.status = com.example.accounting.EntryStatus.POSTED and e.reversalOf is null
			""")
	long countActiveClosesAfter(@Param("year") int year);

	@Query("""
			select count(e) from JournalEntry e where e.status = com.example.accounting.EntryStatus.DRAFT
			and e.entryDate between :from and :to
			""")
	long countDraftsBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

}
