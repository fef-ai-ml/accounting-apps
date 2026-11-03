package com.example.ui.accounting;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Flat text for an audit row's details, such as {@code name: Kas → Kas Kecil}. */
final class AuditDetails {

	private AuditDetails() {
	}

	static String format(Map<String, Object> details) {
		if (details == null) {
			return "";
		}
		if (details.get("setting") != null && details.containsKey("old") && details.containsKey("new")) {
			return details.get("setting") + ": " + plain(details.get("old")) + " → " + plain(details.get("new"));
		}
		return new TreeMap<>(details).entrySet().stream()
				.map(field -> field.getKey() + ": " + text(field.getValue()))
				.collect(Collectors.joining(", "));
	}

	private static String text(Object value) {
		if (value instanceof Map<?, ?> change && change.containsKey("from") && change.containsKey("to")) {
			return plain(change.get("from")) + " → " + plain(change.get("to"));
		}
		return plain(value);
	}

	private static String plain(Object value) {
		return value == null ? "(none)" : value.toString();
	}

}
