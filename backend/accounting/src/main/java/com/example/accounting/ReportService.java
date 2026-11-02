package com.example.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computed live from posted lines (Phase 1 stores no balances). The native queries return only this tenant's rows
 * through row-level security. A reversed entry still counts: its reversal, also posted, nets it to zero.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	@PersistenceContext
	private EntityManager entityManager;

	private final AccountRepository accounts;

	private final Clock clock;

	ReportService(AccountRepository accounts, Clock clock) {
		this.accounts = accounts;
		this.clock = clock;
	}

	public TrialBalance trialBalance(LocalDate requestedAsOf) {
		LocalDate asOf = requestedAsOf != null ? requestedAsOf : LocalDate.now(clock);
		@SuppressWarnings("unchecked")
		List<Object[]> found = entityManager.createNativeQuery("""
				select a.id, a.code, a.name, a.type, sum(l.debit), sum(l.credit)
				from accounting.account a
				join accounting.journal_line l on l.tenant_id = a.tenant_id and l.account_id = a.id
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				where e.status in ('POSTED', 'REVERSED') and e.entry_date <= :asOf
				group by a.id, a.code, a.name, a.type
				order by a.code
				""").setParameter("asOf", asOf).getResultList();
		List<TrialBalance.Row> rows = new ArrayList<>();
		BigDecimal totalDebit = ZERO;
		BigDecimal totalCredit = ZERO;
		for (Object[] row : found) {
			AccountType type = AccountType.valueOf((String) row[3]);
			BigDecimal debit = money(row[4]);
			BigDecimal credit = money(row[5]);
			rows.add(new TrialBalance.Row(((Number) row[0]).longValue(), (String) row[1], (String) row[2], type, debit,
					credit, balance(type, debit, credit)));
			totalDebit = totalDebit.add(debit);
			totalCredit = totalCredit.add(credit);
		}
		return new TrialBalance(asOf, rows, totalDebit, totalCredit, totalDebit.subtract(totalCredit));
	}

	public GeneralLedger generalLedger(long accountId, LocalDate from, LocalDate to) {
		if (from.isAfter(to)) {
			throw AccountingException.badRequest("validation", "from must not be after to");
		}
		Account account = accounts.findById(accountId)
				.orElseThrow(() -> new AccountingNotFoundException("Account " + accountId));
		Object[] before = (Object[]) entityManager.createNativeQuery("""
				select coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0)
				from accounting.journal_line l
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				where l.account_id = :account and e.status in ('POSTED', 'REVERSED') and e.entry_date < :from
				""").setParameter("account", accountId).setParameter("from", from).getSingleResult();
		BigDecimal opening = balance(account.getType(), money(before[0]), money(before[1]));
		@SuppressWarnings("unchecked")
		List<Object[]> found = entityManager.createNativeQuery("""
				select cast(e.entry_date as text), e.id, e.entry_no, e.description, l.memo, l.debit, l.credit
				from accounting.journal_line l
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				where l.account_id = :account and e.status in ('POSTED', 'REVERSED')
				  and e.entry_date >= :from and e.entry_date <= :to
				order by e.entry_date, e.entry_no, l.line_no
				""").setParameter("account", accountId).setParameter("from", from).setParameter("to", to).getResultList();
		List<GeneralLedger.Line> lines = new ArrayList<>();
		BigDecimal running = opening;
		for (Object[] row : found) {
			BigDecimal debit = money(row[5]);
			BigDecimal credit = money(row[6]);
			running = running.add(balance(account.getType(), debit, credit));
			lines.add(new GeneralLedger.Line(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue(),
					(String) row[2], (String) row[3], (String) row[4], debit, credit, running));
		}
		return new GeneralLedger(account.getId(), account.getCode(), account.getName(), account.getType(), from, to,
				opening, lines, running);
	}

	private static BigDecimal balance(AccountType type, BigDecimal debit, BigDecimal credit) {
		return type.debitNormal() ? debit.subtract(credit) : credit.subtract(debit);
	}

	private static BigDecimal money(Object value) {
		return ((BigDecimal) value).setScale(2);
	}

}
