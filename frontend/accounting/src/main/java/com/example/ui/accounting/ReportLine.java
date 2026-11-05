package com.example.ui.accounting;

import java.math.BigDecimal;

/** One row of a statement, on screen and in its CSV. A heading has no amount; a total has no account. */
public record ReportLine(String section, String code, String name, int depth, BigDecimal amount, boolean total) {

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getAmountText() {
		return Idr.format(amount);
	}

	public String getStyle() {
		String indent = "padding-left:" + depth * 16 + "px";
		return total || amount == null ? indent + ";font-weight:bold" : indent;
	}

}
