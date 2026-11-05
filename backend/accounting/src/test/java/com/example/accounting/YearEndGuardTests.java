package com.example.accounting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bypasses the services with native SQL as app_runtime: a close must hold even if a service is wrong. */
class YearEndGuardTests extends AccountingTestSupport {

	private static final int CLOSING = 2030;

	/** The 12 open months of the year and two accounts, as {debit, credit}. */
	private long[] fixture() {
		for (int month = 1; month <= 12; month++) {
			insertPeriod(CLOSING, month);
		}
		return new long[] { insertAccount("A", true, true), insertAccount("B", true, true) };
	}

	/** A committed close, as {closing entry, debit account, credit account}. */
	private long[] closed() {
		return inTransaction(() -> {
			long[] accounts = fixture();
			return new long[] { closeBySql(CLOSING, accounts[0], accounts[1]), accounts[0], accounts[1] };
		});
	}

	private long december() {
		return ((Number) entityManager.createNativeQuery(
				"select id from accounting.fiscal_period where year = " + CLOSING + " and month = 12").getSingleResult()).longValue();
	}

	private void markReversed(long original, long reversal) {
		sql("update accounting.journal_entry set status = 'REVERSED', reversed_by = " + reversal
				+ ", version = version + 1 where id = :id", original);
	}

	/** Opens December and posts the mirror closing entry, as reopening a year does. */
	private void reopen(long[] close) {
		inTransaction(() -> {
			sql("update accounting.fiscal_period set status = 'OPEN' where id = :id", december());
			long reversal = closingDraft(CLOSING, close[2], close[1]);
			sql("update accounting.journal_entry set reversal_of = " + close[0] + " where id = :id", reversal);
			postBySql(reversal);
			markReversed(close[0], reversal);
			return null;
		});
	}

	@Test
	void aPostedCloseWithAllMonthsClosedIsAccepted() {
		assertThatCode(this::closed).doesNotThrowAnyException();
	}

	@Test
	void aSecondActiveCloseOfAYearIsRefused() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long[] accounts = fixture();
			postBySql(closingDraft(CLOSING, accounts[0], accounts[1]));
			postBySql(closingDraft(CLOSING, accounts[0], accounts[1]));
			return null;
		})).hasStackTraceContaining("journal_entry_active_close_idx");
	}

	@Test
	void aClosingEntryMustBeDatedTheLastDayOfItsYear() {
		assertThatThrownBy(() -> inTransaction(() -> {
			fixture();
			long entry = insertEntry(december(), "DRAFT");
			return sql("update accounting.journal_entry set closing_year = " + CLOSING + " where id = :id", entry);
		})).hasStackTraceContaining("journal_entry_closing_date_check");
	}

	@Test
	void aClosingEntryCannotStayADraft() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long[] accounts = fixture();
			return closingDraft(CLOSING, accounts[0], accounts[1]);
		})).hasStackTraceContaining("cannot stay a draft");
	}

	@Test
	void anActiveCloseNeedsEveryMonthClosed() {
		// Own transaction for the fixture: deferred triggers fire in event order, so December's INSERT event in the
		// same transaction would report "has an active close" first.
		long[] accounts = inTransaction(this::fixture);
		assertThatThrownBy(() -> inTransaction(() -> {
			postBySql(closingDraft(CLOSING, accounts[0], accounts[1]));
			closeMonthsBySql(CLOSING);
			return sql("update accounting.fiscal_period set status = 'OPEN' where id = :id", december());
		})).hasStackTraceContaining("needs all 12 months");
	}

	@Test
	void aMonthCannotBeOpenedUnderAnActiveClose() {
		closed();
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.fiscal_period set status = 'OPEN' where id = :id", december())))
				.hasStackTraceContaining("has an active close");
	}

	@Test
	void aNormalReversalOfACloseIsRefused() {
		long[] close = closed();
		assertThatThrownBy(() -> inTransaction(() -> {
			long reversal = insertEntry(insertPeriod(CLOSING + 1, 1), "DRAFT");
			insertLine(reversal, 1, close[2], "100.00", "0");
			insertLine(reversal, 2, close[1], "0", "100.00");
			sql("update accounting.journal_entry set reversal_of = " + close[0] + " where id = :id", reversal);
			postBySql(reversal);
			markReversed(close[0], reversal);
			return null;
		})).hasStackTraceContaining("different closing year");
	}

	@Test
	void aClosingReversalOfANormalEntryIsRefused() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long[] accounts = fixture();
			long normal = insertEntry(december(), "DRAFT");
			insertLine(normal, 1, accounts[0], "100.00", "0");
			insertLine(normal, 2, accounts[1], "0", "100.00");
			postBySql(normal);
			long reversal = closingDraft(CLOSING, accounts[1], accounts[0]);
			sql("update accounting.journal_entry set reversal_of = " + normal + " where id = :id", reversal);
			postBySql(reversal);
			markReversed(normal, reversal);
			return null;
		})).hasStackTraceContaining("different closing year");
	}

	@Test
	void aReopenedYearCanBeClosedAgain() {
		long[] close = closed();
		reopen(close);
		assertThatCode(() -> inTransaction(() -> closeBySql(CLOSING, close[1], close[2]))).doesNotThrowAnyException();
	}

}
