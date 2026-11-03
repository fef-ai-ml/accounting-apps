package com.example.ui.accounting;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.zkoss.zul.ListModel;
import org.zkoss.zul.ext.Selectable;

import static org.assertj.core.api.Assertions.assertThat;

class EntryAccountModelTests {

	@Test
	void aSelectionInOneRowsModelDoesNotReachAnotherRow() {
		List<AccountDto> options = List.of(new AccountDto(2L, "1110", "Kas Kecil", "ASSET", 1L, true, true));

		ListModel<Object> row1 = EntryViewModel.accountModel(options);
		ListModel<Object> row2 = EntryViewModel.accountModel(options);
		((Selectable<Object>) row1).addToSelection(options.get(0));

		assertThat(((Selectable<Object>) row1).getSelection()).hasSize(1);
		assertThat(((Selectable<Object>) row2).getSelection()).isEmpty();
	}

}
