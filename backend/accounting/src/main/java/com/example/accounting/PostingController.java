package com.example.accounting;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/journal-entries")
public class PostingController {

	private final PostingService service;

	public PostingController(PostingService service) {
		this.service = service;
	}

	@PostMapping("/{id}/post")
	@PreAuthorize("hasAuthority('accounting:post')")
	public EntryResponse post(@PathVariable long id, @RequestParam long version) {
		return service.post(id, version);
	}

	@PostMapping("/{id}/reverse")
	@PreAuthorize("hasAuthority('accounting:post')")
	public ReverseResponse reverse(@PathVariable long id, @Valid @RequestBody ReverseRequest request) {
		return service.reverse(id, request);
	}

}
