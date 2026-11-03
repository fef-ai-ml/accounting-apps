package com.example.accounting;

import java.time.Instant;
import java.util.Map;

public record AuditRow(Instant at, String actorName, AuditAction action, String entity, Long entityId,
		Map<String, Object> details) {
}
