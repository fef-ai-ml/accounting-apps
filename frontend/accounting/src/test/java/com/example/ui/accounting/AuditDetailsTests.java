package com.example.ui.accounting;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditDetailsTests {

	@Test
	void aChangeShowsOldAndNew() {
		assertThat(AuditDetails.format(Map.of("name", Map.of("from", "Kas", "to", "Kas Kecil"))))
				.isEqualTo("name: Kas → Kas Kecil");
	}

	@Test
	void aMissingSideShowsAsNone() {
		Map<String, Object> change = new HashMap<>();
		change.put("from", null);
		change.put("to", "1100");

		assertThat(AuditDetails.format(Map.of("parent", change))).isEqualTo("parent: (none) → 1100");
	}

	@Test
	void plainValuesAndSeveralFieldsAreFlatAndOrdered() {
		assertThat(AuditDetails.format(Map.of("version", 3, "entryNo", "JE-1", "totalDebit", "10000000.00")))
				.isEqualTo("entryNo: JE-1, totalDebit: 10000000.00, version: 3");
	}

	@Test
	void noDetailsShowNothing() {
		assertThat(AuditDetails.format(null)).isEmpty();
		assertThat(AuditDetails.format(Map.of())).isEmpty();
	}

	@Test
	void aSettingChangeReadsAsOldToNew() {
		assertThat(AuditDetails.format(Map.of("setting", "require_separate_poster", "old", true, "new", false)))
				.isEqualTo("require_separate_poster: true → false");
	}

	@Test
	void aFlatMapWithoutBothOldAndNewIsUnchanged() {
		assertThat(AuditDetails.format(Map.of("setting", "x", "new", false))).isEqualTo("new: false, setting: x");
	}

}
