package com.example.ui.accounting;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Amounts as Indonesian readers expect them: 1.665.000,00. */
public final class Idr {

	private Idr() {
	}

	public static String format(BigDecimal amount) {
		if (amount == null) {
			return "";
		}
		// DecimalFormat is not thread-safe, so each call makes its own.
		return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("id-ID"))).format(amount);
	}

}
