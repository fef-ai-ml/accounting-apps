package com.example.ui.accounting;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public record AuditRowDto(Instant at, String actorName, String action, String entity, Long entityId,
		Map<String, Object> details) {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.of("Asia/Jakarta"));

	public String getAtText() {
		return at == null ? "" : TIME.format(at);
	}

	public String getActorName() {
		return actorName;
	}

	public String getAction() {
		return action;
	}

	public String getRecord() {
		return entityId == null ? entity : entity + " " + entityId;
	}

	public String getDetailsText() {
		return AuditDetails.format(details);
	}

}
