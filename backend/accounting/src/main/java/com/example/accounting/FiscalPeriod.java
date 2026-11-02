package com.example.accounting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "fiscal_period", schema = "accounting")
public class FiscalPeriod {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "year", nullable = false)
	private int year;

	@Column(name = "month", nullable = false)
	private int month;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PeriodStatus status = PeriodStatus.OPEN;

	@Column(name = "closed_by_sub", length = 100)
	private String closedBySub;

	@Column(name = "closed_by_name", length = 200)
	private String closedByName;

	@Column(name = "closed_at")
	private Instant closedAt;

	protected FiscalPeriod() {
	}

	FiscalPeriod(int year, int month) {
		this.year = year;
		this.month = month;
		YearMonth yearMonth = YearMonth.of(year, month);
		this.startDate = yearMonth.atDay(1);
		this.endDate = yearMonth.atEndOfMonth();
	}

	void close(Actor actor, Instant at) {
		this.status = PeriodStatus.CLOSED;
		this.closedBySub = actor.sub();
		this.closedByName = actor.name();
		this.closedAt = at;
	}

	void reopen() {
		this.status = PeriodStatus.OPEN;
		this.closedBySub = null;
		this.closedByName = null;
		this.closedAt = null;
	}

	public Long getId() {
		return id;
	}

	public int getYear() {
		return year;
	}

	public int getMonth() {
		return month;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public PeriodStatus getStatus() {
		return status;
	}

	public String getClosedByName() {
		return closedByName;
	}

	public Instant getClosedAt() {
		return closedAt;
	}

}
