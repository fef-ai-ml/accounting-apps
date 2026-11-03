package com.example.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "journal_entry", schema = "accounting")
@SQLRestriction("deleted_at is null")
public class JournalEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "entry_no", length = 20)
	private String entryNo;

	@Column(name = "entry_date", nullable = false)
	private LocalDate entryDate;

	@Column(name = "period_id", nullable = false)
	private Long periodId;

	@Column(length = 100)
	private String reference;

	@Column(nullable = false, length = 500)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private EntryStatus status = EntryStatus.DRAFT;

	@Column(name = "reversal_of")
	private Long reversalOf;

	@Column(name = "reversed_by")
	private Long reversedBy;

	@Column(name = "created_by_sub", nullable = false, length = 100, updatable = false)
	private String createdBySub;

	@Column(name = "created_by_name", nullable = false, length = 200, updatable = false)
	private String createdByName;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "last_modified_by_sub", nullable = false, length = 100)
	private String lastModifiedBySub;

	@Column(name = "last_modified_by_name", nullable = false, length = 200)
	private String lastModifiedByName;

	@Column(name = "last_modified_at", nullable = false)
	private Instant lastModifiedAt;

	@Column(name = "posted_by_sub", length = 100)
	private String postedBySub;

	@Column(name = "posted_by_name", length = 200)
	private String postedByName;

	@Column(name = "posted_at")
	private Instant postedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by_sub", length = 100)
	private String deletedBySub;

	@Column(name = "deleted_by_name", length = 200)
	private String deletedByName;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "prepared_by_subs", nullable = false)
	private List<String> preparedBySubs = new ArrayList<>();

	@Version
	private Long version;

	// No REMOVE and no orphanRemoval: Hibernate must have no path to deleting a line.
	@OneToMany(mappedBy = "entry", cascade = { CascadeType.PERSIST, CascadeType.MERGE })
	@OrderBy("lineNo")
	@BatchSize(size = 200)
	@SQLRestriction("deleted_at is null")
	private List<JournalLine> lines = new ArrayList<>();

	protected JournalEntry() {
	}

	/** Marks every live line removed and empties the list; flush before adding lines, Hibernate inserts before it updates. */
	void removeLines(Actor actor, Instant at) {
		lines.forEach(line -> line.markDeleted(actor, at));
		lines.clear();
	}

	void markDeleted(Actor actor, Instant at) {
		this.deletedAt = at;
		this.deletedBySub = actor.sub();
		this.deletedByName = actor.name();
		removeLines(actor, at);
	}

	JournalEntry(LocalDate entryDate, Long periodId, String reference, String description, Actor actor, Instant at) {
		this.createdBySub = actor.sub();
		this.createdByName = actor.name();
		this.createdAt = at;
		edit(entryDate, periodId, reference, description, actor, at);
	}

	void edit(LocalDate entryDate, Long periodId, String reference, String description, Actor actor, Instant at) {
		this.entryDate = entryDate;
		this.periodId = periodId;
		this.reference = reference;
		this.description = description;
		this.lastModifiedBySub = actor.sub();
		this.lastModifiedByName = actor.name();
		this.lastModifiedAt = at;
		if (!preparedBySubs.contains(actor.sub())) {
			preparedBySubs.add(actor.sub());
		}
	}

	/** Never empty: a draft written without the list (old instance, raw SQL) must still block its own maker. */
	Set<String> preparers() {
		Set<String> all = new HashSet<>(preparedBySubs);
		all.add(createdBySub);
		all.add(lastModifiedBySub);
		return all;
	}

	void addLine(Long accountId, Side side, String memo) {
		lines.add(new JournalLine(this, lines.size() + 1, accountId, side.debit(), side.credit(), memo));
	}

	void reversalOf(Long originalId) {
		this.reversalOf = originalId;
	}

	void markPosted(String entryNo, Actor actor, Instant at) {
		this.entryNo = entryNo;
		this.status = EntryStatus.POSTED;
		this.postedBySub = actor.sub();
		this.postedByName = actor.name();
		this.postedAt = at;
	}

	void markReversed(Long reversedBy) {
		this.status = EntryStatus.REVERSED;
		this.reversedBy = reversedBy;
	}

	BigDecimal totalDebit() {
		return lines.stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
	}

	List<Side> sides() {
		return lines.stream().map(JournalLine::side).toList();
	}

	public Long getId() {
		return id;
	}

	public String getEntryNo() {
		return entryNo;
	}

	public LocalDate getEntryDate() {
		return entryDate;
	}

	public Long getPeriodId() {
		return periodId;
	}

	public String getReference() {
		return reference;
	}

	public String getDescription() {
		return description;
	}

	public EntryStatus getStatus() {
		return status;
	}

	public Long getReversalOf() {
		return reversalOf;
	}

	public Long getReversedBy() {
		return reversedBy;
	}

	public String getCreatedBySub() {
		return createdBySub;
	}

	public String getCreatedByName() {
		return createdByName;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getLastModifiedBySub() {
		return lastModifiedBySub;
	}

	public String getLastModifiedByName() {
		return lastModifiedByName;
	}

	public Instant getLastModifiedAt() {
		return lastModifiedAt;
	}

	public String getPostedByName() {
		return postedByName;
	}

	public Instant getPostedAt() {
		return postedAt;
	}

	public Long getVersion() {
		return version;
	}

	List<JournalLine> getLines() {
		return lines;
	}

}
