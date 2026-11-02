package com.example.accounting;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting")
public class SetupController {

	private final SetupService service;

	public SetupController(SetupService service) {
		this.service = service;
	}

	@PostMapping("/setup")
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public SettingsResponse setup() {
		return service.setup();
	}

	@GetMapping("/settings")
	@PreAuthorize("hasAuthority('accounting:read')")
	public SettingsResponse settings() {
		return service.settings();
	}

	@PutMapping("/settings")
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public SettingsResponse update(@Valid @RequestBody SettingsRequest request) {
		return service.update(request);
	}

}
