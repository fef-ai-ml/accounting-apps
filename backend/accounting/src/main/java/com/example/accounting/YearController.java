package com.example.accounting;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/years")
public class YearController {

	private final YearEndService service;

	public YearController(YearEndService service) {
		this.service = service;
	}

	@GetMapping("/{year}")
	@PreAuthorize("hasAuthority('accounting:read')")
	public YearStatus status(@PathVariable int year) {
		return service.status(year);
	}

	@PostMapping("/{year}/close")
	@PreAuthorize("hasAuthority('accounting:period-manage')")
	public YearCloseResponse close(@PathVariable int year, @Valid @RequestBody YearCloseRequest request) {
		return service.close(year, request.retainedEarningsAccountId());
	}

}
