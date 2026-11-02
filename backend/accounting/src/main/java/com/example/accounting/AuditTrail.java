package com.example.accounting;

import java.time.Clock;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class AuditTrail {

	private final AuditLogRepository repository;

	private final JsonMapper json;

	private final Clock clock;

	AuditTrail(AuditLogRepository repository, JsonMapper json, Clock clock) {
		this.repository = repository;
		this.json = json;
		this.clock = clock;
	}

	void record(AuditAction action, String entity, Long entityId, Map<String, ?> details) {
		repository.save(new AuditLog(action, entity, entityId, Actor.current(), clock.instant(),
				json.writeValueAsString(details)));
	}

}
