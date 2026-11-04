package com.example.accounting;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/** The bookkeeping rules that need no database, so they can be tested alone. */
final class JournalRules {

	static final int MAX_LINES = 500;

	private static final int MAX_INTEGER_DIGITS = 17;

	private JournalRules() {
	}

	/** Normalizes to two decimals; refuses anything that would need rounding or does not fit NUMERIC(19,2). */
	static BigDecimal amount(BigDecimal value) {
		if (value == null) {
			return BigDecimal.ZERO.setScale(2);
		}
		// Checked before setScale: an exponent like 1E+1000000000 would otherwise expand into a billion digits.
		BigDecimal stripped = value.stripTrailingZeros();
		if (stripped.scale() > 2 || (long) stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
			throw AccountingException.badRequest("invalid-amount", "Amount " + value.toEngineeringString()
					+ " must have at most " + MAX_INTEGER_DIGITS + " integer digits and 2 decimals");
		}
		return stripped.setScale(2);
	}

	static void checkLine(Side side) {
		boolean debit = side.debit().signum() > 0;
		boolean credit = side.credit().signum() > 0;
		if (debit == credit || side.debit().signum() < 0 || side.credit().signum() < 0) {
			throw AccountingException.badRequest("invalid-line",
					"Every line needs exactly one side greater than zero");
		}
	}

	static void checkBalanced(List<Side> sides) {
		if (sides.size() < 2) {
			throw AccountingException.unprocessable("too-few-lines", "An entry needs at least two lines to be posted");
		}
		BigDecimal debit = sides.stream().map(Side::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal credit = sides.stream().map(Side::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
		if (debit.compareTo(credit) != 0 || debit.signum() <= 0) {
			throw AccountingException.unprocessable("unbalanced",
					"Debit " + debit.toPlainString() + " does not equal credit " + credit.toPlainString());
		}
	}

	static boolean mayPost(String posterSub, Collection<String> preparedBySubs, boolean requireSeparatePoster) {
		return !requireSeparatePoster || !preparedBySubs.contains(posterSub);
	}

}
