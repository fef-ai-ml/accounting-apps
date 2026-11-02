package com.example.accounting;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/accounts")
public class AccountController {

	private final AccountService service;

	public AccountController(AccountService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('accounting:read')")
	public List<AccountResponse> list() {
		return service.list();
	}

	@PostMapping
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public ResponseEntity<AccountResponse> create(@Valid @RequestBody AccountRequest request) {
		AccountResponse created = service.create(request);
		return ResponseEntity.created(URI.create("/api/accounting/accounts/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public AccountResponse update(@PathVariable long id, @Valid @RequestBody AccountRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

}
