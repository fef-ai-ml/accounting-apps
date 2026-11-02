package com.example.accounting;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;

/**
 * Gapless numbers per tenant and year. The upsert holds the sequence row's lock until the transaction ends, so
 * concurrent posts wait, and a rollback gives the number back.
 */
@Component
class EntryNumbers {

	@PersistenceContext
	private EntityManager entityManager;

	String next(int year) {
		long value = ((Number) entityManager.createNativeQuery("""
				insert into accounting.entry_sequence (tenant_id, year, next_value)
				values (cast(nullif(current_setting('app.tenant_id', true), '') as uuid), :year, 2)
				on conflict (tenant_id, year) do update set next_value = accounting.entry_sequence.next_value + 1
				returning next_value - 1
				""").setParameter("year", year).getSingleResult()).longValue();
		return "JE-%d-%06d".formatted(year, value);
	}

}
