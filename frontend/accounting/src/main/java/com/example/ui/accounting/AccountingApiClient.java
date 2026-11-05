package com.example.ui.accounting;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.ui.common.ModuleRestClients;

@Component
public class AccountingApiClient {

	private static final String PATH = "/api/accounting";

	private static final String ENTRIES = PATH + "/journal-entries";

	private final RestClient client;

	public AccountingApiClient(ModuleRestClients clients) {
		this.client = clients.forModule(AccountingUiModule.ID);
	}

	public SettingsDto settings() {
		return client.get().uri(PATH + "/settings").retrieve().body(SettingsDto.class);
	}

	public SettingsDto setup() {
		return client.post().uri(PATH + "/setup").retrieve().body(SettingsDto.class);
	}

	public SettingsDto updateSettings(boolean requireSeparatePoster) {
		return client.put().uri(PATH + "/settings").body(Map.of("requireSeparatePoster", requireSeparatePoster))
				.retrieve().body(SettingsDto.class);
	}

	public List<AccountDto> accounts() {
		return client.get().uri(PATH + "/accounts").retrieve().body(new ParameterizedTypeReference<>() {
		});
	}

	public AccountDto createAccount(AccountRequest request) {
		return client.post().uri(PATH + "/accounts").body(request).retrieve().body(AccountDto.class);
	}

	public AccountDto updateAccount(long id, AccountRequest request) {
		return client.put().uri(PATH + "/accounts/{id}", id).body(request).retrieve().body(AccountDto.class);
	}

	public List<PeriodDto> periods(int year) {
		return client.get().uri(PATH + "/periods?year={year}", year).retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
	}

	public List<PeriodDto> openYear(int year) {
		return client.post().uri(PATH + "/periods/year/{year}", year).retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
	}

	public PeriodDto closePeriod(long id) {
		return client.post().uri(PATH + "/periods/{id}/close", id).retrieve().body(PeriodDto.class);
	}

	public PeriodDto reopenPeriod(long id, String reason) {
		return client.post().uri(PATH + "/periods/{id}/reopen", id).body(Map.of("reason", reason)).retrieve()
				.body(PeriodDto.class);
	}

	public YearStatusDto yearStatus(int year) {
		return client.get().uri(PATH + "/years/{year}", year).retrieve().body(YearStatusDto.class);
	}

	public YearCloseResultDto closeYear(int year, long retainedEarningsAccountId) {
		return client.post().uri(PATH + "/years/{year}/close", year).body(Map.of("retainedEarningsAccountId", retainedEarningsAccountId))
				.retrieve().body(YearCloseResultDto.class);
	}

	public YearStatusDto reopenYear(int year, String reason) {
		return client.post().uri(PATH + "/years/{year}/reopen", year).body(Map.of("reason", reason)).retrieve().body(YearStatusDto.class);
	}

	public EntryPageDto entries(EntryFilter filter) {
		return client.get().uri(uri -> uri.path(ENTRIES)
				.queryParamIfPresent("from", Optional.ofNullable(filter.from()))
				.queryParamIfPresent("to", Optional.ofNullable(filter.to()))
				.queryParamIfPresent("status", Optional.ofNullable(filter.status()).filter(s -> !s.isBlank()))
				.queryParamIfPresent("accountId", Optional.ofNullable(filter.accountId()))
				.queryParamIfPresent("q", Optional.ofNullable(filter.q()).filter(q -> !q.isBlank()))
				.queryParam("page", filter.page())
				.queryParam("size", filter.size())
				.build()).retrieve().body(EntryPageDto.class);
	}

	public AuditPageDto audit(LocalDate from, LocalDate to, String action, int page, int size) {
		return client.get().uri(uri -> uri.path(PATH + "/audit")
				.queryParamIfPresent("from", Optional.ofNullable(from))
				.queryParamIfPresent("to", Optional.ofNullable(to))
				.queryParamIfPresent("action", Optional.ofNullable(action).filter(a -> !a.isBlank()))
				.queryParam("page", page)
				.queryParam("size", size)
				.build()).retrieve().body(AuditPageDto.class);
	}

	public EntryDto entry(long id) {
		return client.get().uri(ENTRIES + "/{id}", id).retrieve().body(EntryDto.class);
	}

	public EntryDto createEntry(EntryRequest request) {
		return client.post().uri(ENTRIES).body(request).retrieve().body(EntryDto.class);
	}

	public EntryDto updateEntry(long id, EntryRequest request) {
		return client.put().uri(ENTRIES + "/{id}", id).body(request).retrieve().body(EntryDto.class);
	}

	public void deleteEntry(long id, long version) {
		client.delete().uri(ENTRIES + "/{id}?version={version}", id, version).retrieve().toBodilessEntity();
	}

	public EntryDto postEntry(long id, long version) {
		return client.post().uri(ENTRIES + "/{id}/post?version={version}", id, version).retrieve().body(EntryDto.class);
	}

	public ReverseResultDto reverseEntry(long id, LocalDate date, boolean copyToDraft) {
		Map<String, Object> body = date == null ? Map.of("copyToDraft", copyToDraft)
				: Map.of("date", date.toString(), "copyToDraft", copyToDraft);
		return client.post().uri(ENTRIES + "/{id}/reverse", id).body(body).retrieve().body(ReverseResultDto.class);
	}

	public TrialBalanceDto trialBalance(LocalDate asOf) {
		return client.get().uri(PATH + "/reports/trial-balance?asOf={asOf}", asOf).retrieve().body(TrialBalanceDto.class);
	}

	public GeneralLedgerDto generalLedger(long accountId, LocalDate from, LocalDate to) {
		return client.get().uri(PATH + "/reports/general-ledger?accountId={account}&from={from}&to={to}", accountId, from, to)
				.retrieve().body(GeneralLedgerDto.class);
	}

	public IncomeStatementDto incomeStatement(LocalDate from, LocalDate to) {
		return client.get().uri(PATH + "/reports/income-statement?from={from}&to={to}", from, to).retrieve()
				.body(IncomeStatementDto.class);
	}

	public BalanceSheetDto balanceSheet(LocalDate asOf) {
		return client.get().uri(PATH + "/reports/balance-sheet?asOf={asOf}", asOf).retrieve().body(BalanceSheetDto.class);
	}

}
