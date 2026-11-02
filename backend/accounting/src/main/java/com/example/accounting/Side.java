package com.example.accounting;

import java.math.BigDecimal;

record Side(BigDecimal debit, BigDecimal credit) {

	Side swapped() {
		return new Side(credit, debit);
	}

}
