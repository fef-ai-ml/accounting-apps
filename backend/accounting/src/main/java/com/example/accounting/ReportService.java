package com.example.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

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

	private static final String LINES_THROUGH = """
			select l.account_id, sum(l.debit) - sum(l.credit)
			from accounting.journal_line l
			join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
			where e.status in ('POSTED', 'REVERSED') and l.deleted_at is null and e.entry_date <= :to
			  and l.tenant_id = cast(nullif(current_setting('app.tenant_id', true), '') as uuid)
			""";

	private static final String BY_ACCOUNT = " group by l.account_id";

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
		// The one query with no other tenant predicate: were the API ever run as the owner, RLS would not apply here.
		List<Object[]> found = entityManager.createNativeQuery("""
				select a.id, a.code, a.name, a.type, sum(l.debit), sum(l.credit)
				from accounting.account a
				join accounting.journal_line l on l.tenant_id = a.tenant_id and l.account_id = a.id
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				where e.status in ('POSTED', 'REVERSED') and e.entry_date <= :asOf and l.deleted_at is null
				  and a.tenant_id = cast(nullif(current_setting('app.tenant_id', true), '') as uuid)
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
				where l.account_id = :account and e.status in ('POSTED', 'REVERSED') and e.entry_date < :from and l.deleted_at is null
				""").setParameter("account", accountId).setParameter("from", from).getSingleResult();
		BigDecimal opening = balance(account.getType(), money(before[0]), money(before[1]));
		@SuppressWarnings("unchecked")
		List<Object[]> found = entityManager.createNativeQuery("""
				select cast(e.entry_date as text), e.id, e.entry_no, e.description, l.memo, l.debit, l.credit
				from accounting.journal_line l
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				where l.account_id = :account and e.status in ('POSTED', 'REVERSED') and l.deleted_at is null
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

	/** Leaves out closes and their reversals, so a year reads the same before a close, after it and after a reopen. */
	public IncomeStatement incomeStatement(LocalDate from, LocalDate to) {
		JournalService.checkDates(from, to);
		Map<Long, BigDecimal> found = collect(entityManager
				.createNativeQuery(LINES_THROUGH + " and e.closing_year is null and e.entry_date >= :from" + BY_ACCOUNT)
				.setParameter("to", to).setParameter("from", from));
		List<Account> all = accounts.findAll();
		Section revenue = section(AccountType.REVENUE, all, found);
		Section expenses = section(AccountType.EXPENSE, all, found);
		return new IncomeStatement(from, to, revenue.rows(), expenses.rows(), revenue.total(), expenses.total(),
				revenue.total().subtract(expenses.total()));
	}

	public BalanceSheet balanceSheet(LocalDate requestedAsOf) {
		LocalDate asOf = requestedAsOf != null ? requestedAsOf : LocalDate.now(clock);
		Map<Long, BigDecimal> found = debitMinusCredit(asOf);
		List<Account> all = accounts.findAll();
		Section assets = section(AccountType.ASSET, all, found);
		Section liabilities = section(AccountType.LIABILITY, all, found);
		Section equity = section(AccountType.EQUITY, all, found);
		BigDecimal earnings = section(AccountType.REVENUE, all, found).total()
				.subtract(section(AccountType.EXPENSE, all, found).total());
		BigDecimal totalEquity = equity.total().add(earnings);
		BigDecimal liabilitiesAndEquity = liabilities.total().add(totalEquity);
		return new BalanceSheet(asOf, assets.rows(), liabilities.rows(), equity.rows(), earnings, assets.total(),
				liabilities.total(), totalEquity, liabilitiesAndEquity, assets.total().subtract(liabilitiesAndEquity));
	}

	/** Debit minus credit per account through the date, closing entries included; an account with no lines is absent. */
	public Map<Long, BigDecimal> debitMinusCredit(LocalDate through) {
		return collect(entityManager.createNativeQuery(LINES_THROUGH + BY_ACCOUNT).setParameter("to", through));
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, BigDecimal> collect(Query query) {
		Map<Long, BigDecimal> found = new HashMap<>();
		for (Object[] row : (List<Object[]>) query.getResultList()) {
			found.put(((Number) row[0]).longValue(), money(row[1]));
		}
		return found;
	}

	private static Section section(AccountType type, List<Account> all, Map<Long, BigDecimal> debitMinusCredit) {
		List<StatementHierarchy.Node> nodes = new ArrayList<>();
		Map<Long, BigDecimal> balances = new HashMap<>();
		BigDecimal total = ZERO;
		for (Account account : all) {
			if (account.getType() != type) {
				continue;
			}
			nodes.add(new StatementHierarchy.Node(account.getId(), account.getCode(), account.getName(), account.getParentId()));
			BigDecimal raw = debitMinusCredit.getOrDefault(account.getId(), ZERO);
			BigDecimal balance = type.debitNormal() ? raw : raw.negate();
			balances.put(account.getId(), balance);
			total = total.add(balance);
		}
		return new Section(StatementHierarchy.rows(nodes, balances), total);
	}

	private static BigDecimal balance(AccountType type, BigDecimal debit, BigDecimal credit) {
		return type.debitNormal() ? debit.subtract(credit) : credit.subtract(debit);
	}

	private static BigDecimal money(Object value) {
		return ((BigDecimal) value).setScale(2);
	}

	private record Section(List<StatementRow> rows, BigDecimal total) {
	}

}
