package com.example.accounting;

import java.net.URI;
import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/journal-entries")
public class JournalController {

	private final JournalService service;

	public JournalController(JournalService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('accounting:read')")
	public EntryPage list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) EntryStatus status,
			@RequestParam(required = false) Long accountId,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return service.list(from, to, status, accountId, q, page, size);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:read')")
	public EntryResponse get(@PathVariable long id) {
		return service.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('accounting:write')")
	public ResponseEntity<EntryResponse> create(@Valid @RequestBody EntryRequest request) {
		EntryResponse created = service.create(request);
		return ResponseEntity.created(URI.create("/api/accounting/journal-entries/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:write')")
	public EntryResponse update(@PathVariable long id, @Valid @RequestBody EntryRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:write')")
	public ResponseEntity<Void> delete(@PathVariable long id, @RequestParam long version) {
		service.delete(id, version);
		return ResponseEntity.noContent().build();
	}

}
