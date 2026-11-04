package com.example.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class AuditService {

	private final AuditLogRepository repository;

	private final JsonMapper json;

	private final Clock clock;

	AuditService(AuditLogRepository repository, JsonMapper json, Clock clock) {
		this.repository = repository;
		// A double would show 10000000.00 as 1.0E7.
		this.json = json.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
		this.clock = clock;
	}

	public AuditPage list(LocalDate from, LocalDate to, AuditAction action, int page, int size) {
		JournalService.checkPaging(page, size);
		JournalService.checkDates(from, to);
		Page<AuditLog> result = repository.findAll(filter(from, to, action),
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "at", "id")));
		return new AuditPage(result.stream().map(this::row).toList(), result.getTotalElements(), page, size);
	}

	private AuditRow row(AuditLog log) {
		return new AuditRow(log.getAt(), log.getActorName(), log.getAction(), log.getEntity(), log.getEntityId(),
				details(log.getDetails()));
	}

	private Map<String, Object> details(String text) {
		if (text == null) {
			return Map.of();
		}
		Map<String, Object> parsed = json.readValue(text, new TypeReference<Map<String, Object>>() {
		});
		parsed.replaceAll((key, value) -> value instanceof BigDecimal amount ? amount.toPlainString() : value);
		return parsed;
	}

	private Specification<AuditLog> filter(LocalDate from, LocalDate to, AuditAction action) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("at"), from.atStartOfDay(clock.getZone()).toInstant()));
			}
			if (to != null) {
				predicates.add(cb.lessThan(root.<Instant>get("at"), to.plusDays(1).atStartOfDay(clock.getZone()).toInstant()));
			}
			if (action != null) {
				predicates.add(cb.equal(root.get("action"), action));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

}
