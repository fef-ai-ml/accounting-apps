package com.example.accounting;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FiscalPeriodRepository extends JpaRepository<FiscalPeriod, Long> {

	boolean existsByYear(int year);

	List<FiscalPeriod> findByYearOrderByMonth(int year);

	Optional<FiscalPeriod> findByYearAndMonth(int year, int month);

	/** Posting holds this until commit, so a close waits for it and sees the posted entry. */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select p from FiscalPeriod p where p.year = :year and p.month = :month")
	Optional<FiscalPeriod> lockForPosting(@Param("year") int year, @Param("month") int month);

	/** Closing holds this until commit, so a racing post waits and then sees CLOSED. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from FiscalPeriod p where p.id = :id")
	Optional<FiscalPeriod> lockForClosing(@Param("id") long id);

	/** A post holds its period FOR SHARE, so it finishes first and is counted, or waits and then sees CLOSED. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from FiscalPeriod p where p.year = :year order by p.month")
	List<FiscalPeriod> lockYear(@Param("year") int year);

	/** A post into an earlier year finishes first and trips earlier-year-open, or waits until this close commits. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from FiscalPeriod p where p.year < :year order by p.year, p.month")
	List<FiscalPeriod> lockEarlierYears(@Param("year") int year);

	@Query(nativeQuery = true, value = """
			select count(*) from accounting.journal_entry
			where period_id = :id and status = 'DRAFT' and deleted_at is null
			""")
	long countDrafts(@Param("id") long id);

}
