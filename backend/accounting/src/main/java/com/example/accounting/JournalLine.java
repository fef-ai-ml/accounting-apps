package com.example.accounting;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "journal_line", schema = "accounting")
class JournalLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "entry_id", nullable = false, updatable = false)
	private JournalEntry entry;

	@Column(name = "line_no", nullable = false)
	private int lineNo;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal debit;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal credit;

	@Column(length = 200)
	private String memo;

	protected JournalLine() {
	}

	JournalLine(JournalEntry entry, int lineNo, Long accountId, BigDecimal debit, BigDecimal credit, String memo) {
		this.entry = entry;
		this.lineNo = lineNo;
		this.accountId = accountId;
		this.debit = debit;
		this.credit = credit;
		this.memo = memo;
	}

	Side side() {
		return new Side(debit, credit);
	}

	int getLineNo() {
		return lineNo;
	}

	Long getAccountId() {
		return accountId;
	}

	BigDecimal getDebit() {
		return debit;
	}

	BigDecimal getCredit() {
		return credit;
	}

	String getMemo() {
		return memo;
	}

}
