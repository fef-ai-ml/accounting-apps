package com.example.ui.accounting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditViewModelTests {

	@Test
	void yearActionsCanBeFilteredOn() {
		assertThat(new AuditViewModel().getActions()).contains("YEAR_CLOSE", "YEAR_REOPEN");
	}

}
