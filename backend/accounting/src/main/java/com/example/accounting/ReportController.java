package com.example.accounting;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/reports")
public class ReportController {

	private final ReportService service;

	public ReportController(ReportService service) {
		this.service = service;
	}

	@GetMapping("/trial-balance")
	@PreAuthorize("hasAuthority('accounting:read')")
	public TrialBalance trialBalance(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
		return service.trialBalance(asOf);
	}

	@GetMapping("/general-ledger")
	@PreAuthorize("hasAuthority('accounting:read')")
	public GeneralLedger generalLedger(@RequestParam long accountId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return service.generalLedger(accountId, from, to);
	}

	@GetMapping("/income-statement")
	@PreAuthorize("hasAuthority('accounting:read')")
	public IncomeStatement incomeStatement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return service.incomeStatement(from, to);
	}

	@GetMapping("/balance-sheet")
	@PreAuthorize("hasAuthority('accounting:read')")
	public BalanceSheet balanceSheet(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
		return service.balanceSheet(asOf);
	}

}
