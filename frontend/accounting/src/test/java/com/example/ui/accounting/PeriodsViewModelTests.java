package com.example.ui.accounting;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PeriodsViewModelTests {

	@Test
	void openYearWithABlankYearFieldCallsNoApi() {
		AccountingApiClient client = mock(AccountingApiClient.class);
		PeriodsViewModel vm = new PeriodsViewModel();
		ReflectionTestUtils.setField(vm, "accountingApiClient", client);
		vm.changeYear(null);

		vm.openYear();

		verifyNoInteractions(client);
	}

}
