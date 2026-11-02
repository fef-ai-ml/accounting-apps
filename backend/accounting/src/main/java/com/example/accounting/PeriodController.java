package com.example.accounting;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/periods")
public class PeriodController {

	private final PeriodService service;

	public PeriodController(PeriodService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('accounting:read')")
	public List<PeriodResponse> list(@RequestParam int year) {
		return service.list(year);
	}

	@PostMapping("/year/{year}")
	@PreAuthorize("hasAuthority('accounting:period-manage')")
	public List<PeriodResponse> openYear(@PathVariable int year) {
		return service.openYearChecked(year);
	}

	@PostMapping("/{id}/close")
	@PreAuthorize("hasAuthority('accounting:period-manage')")
	public PeriodResponse close(@PathVariable long id) {
		return service.close(id);
	}

	@PostMapping("/{id}/reopen")
	@PreAuthorize("hasAuthority('accounting:period-manage')")
	public PeriodResponse reopen(@PathVariable long id, @Valid @RequestBody ReopenRequest request) {
		return service.reopen(id, request.reason());
	}

}
