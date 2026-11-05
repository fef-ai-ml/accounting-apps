package com.example.shell;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.zkoss.zk.ui.Component;
import org.zkoss.zul.Button;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Grid;
import org.zkoss.zul.Intbox;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Paging;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Window;

import com.example.ui.accounting.AccountDto;
import com.example.ui.accounting.AccountRequest;
import com.example.ui.accounting.AccountingApiClient;
import com.example.ui.accounting.AuditPageDto;
import com.example.ui.accounting.AuditRowDto;
import com.example.ui.accounting.BalanceSheetDto;
import com.example.ui.accounting.EntryDto;
import com.example.ui.accounting.EntryFilter;
import com.example.ui.accounting.EntryPageDto;
import com.example.ui.accounting.EntrySummaryDto;
import com.example.ui.accounting.EntryViewModel;
import com.example.ui.accounting.GeneralLedgerDto;
import com.example.ui.accounting.IncomeStatementDto;
import com.example.ui.accounting.LineDto;
import com.example.ui.accounting.PeriodDto;
import com.example.ui.accounting.ReverseResultDto;
import com.example.ui.accounting.SettingsDto;
import com.example.ui.accounting.StatementRowDto;
import com.example.ui.accounting.TrialBalanceDto;
import com.example.ui.accounting.YearStatusDto;
import com.example.ui.common.CurrentUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Clicks through the screens with real ZK over its AU protocol and a mocked REST client. It sees what the server does;
 * it cannot see focus or client-side validation, which are README checklist rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AccountingClickPathTests.OpenPages.class)
class AccountingClickPathTests {

	@TestConfiguration(proxyBeanMethods = false)
	static class OpenPages {

		/** Only these test-only URLs skip the Keycloak login. */
		@Bean
		@Order(0)
		SecurityFilterChain openAccountingPages(HttpSecurity http) throws Exception {
			http.securityMatcher("/zkau/web/accounting/**", "/zkau/view/**").authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
			return http.build();
		}

	}

	private static final AccountDto HEADER = new AccountDto(1L, "1100", "Aset Lancar", "ASSET", null, false, true);

	private static final AccountDto KAS = new AccountDto(2L, "1110", "Kas Kecil", "ASSET", 1L, true, true);

	private static final AccountDto BANK = new AccountDto(3L, "1120", "Bank", "ASSET", 1L, true, true);

	private static final AccountDto SALES = new AccountDto(4L, "4100", "Sales", "REVENUE", null, true, true);

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Jakarta"));

	@LocalServerPort
	int port;

	@MockitoBean
	AccountingApiClient api;

	@MockitoBean(name = "currentUser")
	CurrentUser currentUser;

	ZkDriver zk;

	@BeforeEach
	void commonData() {
		zk = new ZkDriver(port);
		can("accounting:read", "accounting:write", "accounting:post", "accounting:account-manage", "accounting:period-manage");
		when(api.accounts()).thenAnswer(call -> {
			zk.captureDesktop();
			return List.of(HEADER, KAS, BANK, SALES);
		});
		when(api.settings()).thenReturn(new SettingsDto(true, true));
		when(api.periods(anyInt())).thenReturn(List.of());
		when(api.entries(any())).thenReturn(new EntryPageDto(List.of(new EntrySummaryDto(7L, null, LocalDate.of(2026, 3, 15), "R",
				"Penjualan", "DRAFT", new BigDecimal("100.00"))), 1, 0, 50));
	}

	private void can(String... permissions) {
		when(currentUser.can(anyString())).thenAnswer(call -> {
			zk.captureDesktop();
			return List.of(permissions).contains(call.getArgument(0));
		});
	}

	private void page(String page) throws Exception {
		zk.open("/zkau/web/accounting/" + page);
	}

	private void newEntry() throws Exception {
		page("journal.zul");
		zk.click(zk.button("New entry"));
	}

	/** Opens the journal's only row, which the mock answers with this entry. */
	private void openEntry(EntryDto entry) throws Exception {
		when(api.entry(7L)).thenReturn(entry);
		page("journal.zul");
		zk.click(zk.all(Listitem.class).get(0));
	}

	private static EntryDto entry(long id, String number, String status, long version) {
		return new EntryDto(id, number, LocalDate.of(2026, 3, 15), 1L, "R", "Desc", status, null, null, "A", "B", version,
				new BigDecimal("100.00"), List.of(new LineDto(1, 2L, new BigDecimal("100.00"), null, ""),
						new LineDto(2, 4L, null, new BigDecimal("100.00"), "")),
				false);
	}

	private static HttpClientErrorException problem(HttpStatus status, String code, String detail) {
		return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
				("{\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

	private List<Window> entryWindows() {
		return zk.all(Window.class).stream().filter(window -> window.getAttribute("vm") instanceof EntryViewModel).toList();
	}

	private EntryViewModel vm() {
		return (EntryViewModel) entryWindows().get(0).getAttribute("vm");
	}

	private void closeEntryWindow() throws Exception {
		zk.au("onClose", entryWindows().get(0).getUuid(), "{}");
	}

	/** Render tests pass on hidden components; this asks whether a person could actually see one. */
	private static boolean shown(Component component) {
		for (Component c = component; c != null; c = c.getParent()) {
			if (!c.isVisible()) {
				return false;
			}
		}
		return true;
	}

	private List<Component> rows() {
		Grid lines = entryWindows().get(0).getChildren().stream().filter(child -> child instanceof Grid grid && grid.getModel() != null)
				.map(Grid.class::cast).findFirst().orElseThrow();
		return lines.getRows().getChildren();
	}

	private Component cell(int row, int column) {
		return rows().get(row).getChildren().get(column);
	}

	private Combobox account(int row) {
		return (Combobox) cell(row, 0);
	}

	private Textbox memo(int row) {
		return (Textbox) cell(row, 1);
	}

	private Decimalbox debit(int row) {
		return (Decimalbox) cell(row, 2);
	}

	private Decimalbox credit(int row) {
		return (Decimalbox) cell(row, 3);
	}

	private Component lineButton(int row, int index) {
		return cell(row, 4).getChildren().get(index);
	}

	private Textbox description() {
		Component header = entryWindows().get(0).getChildren().stream().filter(child -> child instanceof Grid).findFirst().orElseThrow();
		return (Textbox) ((Grid) header).getRows().getLastChild().getLastChild();
	}

	/** The message of the first dialog over the entry window. */
	private String dialogText() {
		return zk.all(Window.class).stream().filter(window -> !(window.getAttribute("vm") instanceof EntryViewModel)).findFirst()
				.map(AccountingClickPathTests::labels).orElse("");
	}

	private static String labels(Component component) {
		StringBuilder text = new StringBuilder(component instanceof Label label ? label.getValue() : "");
		component.getChildren().forEach(child -> text.append(labels(child)));
		return text.toString();
	}

	@Test
	void aPickReachesOnlyItsOwnLine() throws Exception {
		newEntry();

		zk.pick(account(0), KAS.getLabel());
		assertThat(vm().getLines().get(0).getAccount()).isEqualTo(KAS);
		assertThat(vm().getLines().get(1).getAccount()).isNull();
		assertThat(account(1).getValue()).isEmpty();

		zk.pick(account(1), SALES.getLabel());
		assertThat(account(0).getValue()).isEqualTo(KAS.getLabel());
		assertThat(account(1).getValue()).isEqualTo(SALES.getLabel());
	}

	/** A rebuilt row loses keyboard focus, so a person keying an entry gets an empty form. */
	@Test
	void keyingAnEntryKeepsTheRowsItIsTypingIn() throws Exception {
		newEntry();
		zk.pick(account(0), KAS.getLabel());
		zk.change(memo(0), "first");
		Component row = rows().get(0);

		zk.change(debit(0), "100");
		assertThat(rows().get(0)).isSameAs(row);
		zk.change(credit(1), "40");
		assertThat(rows().get(0)).isSameAs(row);
		zk.click(lineButton(1, 0));
		assertThat(rows().get(0)).isSameAs(row);
		assertThat(credit(1).getValue()).isEqualByComparingTo("100");
		zk.change(zk.all(Datebox.class).get(0), "2026-02-01");
		assertThat(rows().get(0)).isSameAs(row);
		zk.click(zk.button("Add line"));
		assertThat(rows().get(0)).isSameAs(row);
		zk.click(lineButton(rows().size() - 1, 1));
		assertThat(rows().get(0)).isSameAs(row);

		assertThat(memo(0).getValue()).isEqualTo("first");
		assertThat(debit(0).getValue()).isEqualByComparingTo("100");
		assertThat(vm().getTotalsText()).contains("100,00");
	}

	@Test
	void nothingEnteredShowsNoBanner() throws Exception {
		newEntry();
		assertThat(vm().getBalanceText()).isEmpty();

		zk.change(debit(0), "5");
		assertThat(vm().getBalanceText()).isEqualTo("Difference 5,00");
	}

	@Test
	void anAccountOnTheLastLineAddsABlankLine() throws Exception {
		newEntry();
		Component row = rows().get(0);

		zk.pick(account(0), KAS.getLabel());
		assertThat(rows()).hasSize(2);

		zk.pick(account(1), SALES.getLabel());
		assertThat(rows()).hasSize(3);
		assertThat(rows().get(0)).isSameAs(row);
		assertThat(vm().getLines().get(2).isBlank()).isTrue();
	}

	@Test
	void typedTextFindsASingleAccountOrWarnsAndLeavesTheLineWithoutOne() throws Exception {
		newEntry();

		zk.change(account(0), "kas");
		assertThat(vm().getLines().get(0).getAccount()).isEqualTo(KAS);
		assertThat(account(0).getValue()).isEqualTo(KAS.getLabel());

		zk.change(account(0), "11");
		assertThat(vm().getLines().get(0).getAccount()).isNull();
		assertThat(account(0).getValue()).isEmpty();
		assertThat(zk.response).contains("No single account matches");

		zk.change(account(0), "");
		assertThat(zk.response).doesNotContain("No single account matches");
	}

	@Test
	void postIsRefusedWhileTheFormHasUnsavedChanges() throws Exception {
		openEntry(entry(7L, null, "DRAFT", 3));

		zk.change(memo(0), "edited");
		zk.click(zk.button("Post"));
		verify(api, never()).postEntry(anyLong(), anyLong());
		assertThat(zk.response).contains("Save the draft first");

		zk.change(memo(0), "");
		when(api.postEntry(7L, 3L)).thenReturn(entry(7L, "JE-1", "POSTED", 4));
		zk.click(zk.button("Post"));
		verify(api).postEntry(7L, 3L);
	}

	@Test
	void anEntryOpensInOneWindowHoweverOftenItsRowIsClicked() throws Exception {
		when(api.entry(7L)).thenReturn(entry(7L, null, "DRAFT", 3));
		page("journal.zul");
		Listitem row = zk.all(Listitem.class).get(0);

		zk.au("onClick", row.getUuid(), "{}", "onClick", row.getUuid(), "{}");
		assertThat(entryWindows()).hasSize(1);
		// The window's id alone keeps the count at 1; without the guard the browser gets this as an alert.
		assertThat(zk.response).doesNotContain("Not unique");
		zk.click(row);
		assertThat(entryWindows()).hasSize(1);

		closeEntryWindow();
		assertThat(entryWindows()).isEmpty();
		zk.click(row);
		assertThat(entryWindows()).hasSize(1);
	}

	@Test
	void theReversalUsesTheEntryTheQuestionWasAskedAbout() throws Exception {
		openEntry(entry(7L, "JE-1", "POSTED", 3));
		when(api.reverseEntry(anyLong(), any(), anyBoolean()))
				.thenReturn(new ReverseResultDto(entry(8L, "JE-2", "POSTED", 0), null));
		when(api.entry(8L)).thenReturn(entry(8L, "JE-2", "POSTED", 0));

		// Two clicks before the first answer; the first Yes puts the reversal on screen.
		zk.click(zk.button("Reverse"));
		zk.click(zk.button("Reverse"));
		zk.click(zk.button("Yes"));
		zk.click(zk.button("Yes"));

		verify(api, never()).reverseEntry(eq(8L), any(), anyBoolean());
		verify(api, times(2)).reverseEntry(eq(7L), any(), anyBoolean());
	}

	@Test
	void theReversalConfirmationStatesTheDateAndAnyDraft() throws Exception {
		openEntry(entry(7L, "JE-1", "POSTED", 3));
		when(api.reverseEntry(anyLong(), any(), anyBoolean())).thenReturn(new ReverseResultDto(entry(8L, "JE-2", "POSTED", 0), null));
		when(api.entry(8L)).thenReturn(entry(8L, "JE-2", "POSTED", 0));

		zk.click(zk.button("Reverse & copy to draft"));
		assertThat(dialogText())
				.isEqualTo("Post a reversal of JE-1 dated " + TODAY + " and copy it to a new draft? This cannot be undone.");
		zk.click(zk.button("No"));
		verify(api, never()).reverseEntry(anyLong(), any(), anyBoolean());

		zk.click(zk.button("Reverse"));
		assertThat(dialogText()).isEqualTo("Post a reversal of JE-1 dated " + TODAY + "? This cannot be undone.");
		zk.click(zk.button("Yes"));
		verify(api).reverseEntry(7L, TODAY, false);
	}

	@Test
	void deleteUsesTheVersionTheQuestionWasAskedAbout() throws Exception {
		openEntry(entry(7L, null, "DRAFT", 3));
		when(api.updateEntry(eq(7L), any())).thenReturn(entry(7L, null, "DRAFT", 4));

		zk.click(zk.button("Delete"));
		zk.click(zk.button("Save draft"));
		zk.click(zk.button("Yes"));

		verify(api).deleteEntry(7L, 3L);
	}

	@Test
	void editThenDeactivateThenSaveDoesNotSendActive() throws Exception {
		when(api.updateAccount(anyLong(), any())).thenReturn(KAS);
		page("accounts.zul");

		zk.click(editOfKas());
		Button save = zk.button("Save");
		zk.click(editOfKas().getNextSibling());
		// Save stays reachable over AU even when the form is hidden.
		zk.click(save);

		ArgumentCaptor<AccountRequest> sent = ArgumentCaptor.forClass(AccountRequest.class);
		verify(api, times(2)).updateAccount(eq(KAS.id()), sent.capture());
		assertThat(sent.getAllValues().get(0).active()).isFalse();
		assertThat(sent.getAllValues().get(1).active()).isNull();
	}

	@Test
	void deactivateClosesTheForm() throws Exception {
		when(api.updateAccount(anyLong(), any())).thenReturn(KAS);
		page("accounts.zul");

		zk.click(editOfKas());
		Button save = zk.button("Save");
		assertThat(shown(save)).isTrue();

		zk.click(editOfKas().getNextSibling());
		assertThat(shown(save)).isFalse();
	}

	private Button editOfKas() {
		return zk.all(Button.class).stream().filter(button -> "Edit".equals(button.getLabel())).skip(1).findFirst().orElseThrow();
	}

	@Test
	void aLedgerRunRendersItsLinesAndTheExportNamesWhatItHolds() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(TODAY, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		when(api.generalLedger(eq(KAS.id()), any(), any())).thenReturn(new GeneralLedgerDto(KAS.id(), "1/10", "Kas Kecil", "ASSET",
				LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 2), new BigDecimal("1.00"),
				List.of(new GeneralLedgerDto.Line(LocalDate.of(2026, 2, 5), 9L, "JE-1", "Sale", "note", new BigDecimal("40.00"), null,
						new BigDecimal("41.00"))),
				new BigDecimal("41.00")));
		page("reports.zul");
		Combobox box = zk.all(Combobox.class).get(0);
		Button run = (Button) box.getNextSibling().getNextSibling().getNextSibling();
		Component export = run.getNextSibling();

		zk.change(box, "kas");
		zk.click(run);

		verify(api).generalLedger(eq(KAS.id()), any(), any());
		assertThat(zk.all(Listcell.class)).extracting(Listcell::getLabel).contains("2026-02-05", "JE-1", "Sale", "note", "40,00", "41,00");
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("1/10 Kas Kecil, 2026-01-01 to 2026-11-02",
				"Opening balance 1,00", "Closing balance 41,00");

		// The box now names another account; what downloads is still the report that ran, and says so.
		zk.change(box, BANK.getLabel());
		zk.click(export);
		List<String> file = zk.download();
		assertThat(file.get(0)).contains("general-ledger-1_10-2026-01-01-2026-11-02.csv");
		assertThat(file.get(1)).contains("General ledger,1/10 Kas Kecil,2026-01-01,2026-11-02\r\nDate,Entry,");
	}

	@Test
	void reportsSayWhatTheyShowFromTheResponse() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(LocalDate.of(2026, 3, 31), List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
				BigDecimal.ZERO));

		page("reports.zul");

		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("As of 2026-03-31");
	}

	private Button nthButton(String label, int index) {
		return zk.all(Button.class).stream().filter(button -> label.equals(button.getLabel())).toList().get(index);
	}

	@Test
	void anIncomeStatementRunRendersAndExportsWhatItHolds() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(TODAY, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		when(api.incomeStatement(eq(TODAY.withDayOfYear(1)), eq(TODAY))).thenReturn(new IncomeStatementDto(LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 3, 31), List.of(new StatementRowDto(1L, "4100", "Penjualan", 0, new BigDecimal("1000.00"))), List.of(),
				new BigDecimal("1000.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("1000.00")));
		page("reports.zul");

		zk.click(nthButton("Run", 2));

		assertThat(zk.all(Listcell.class)).extracting(Listcell::getLabel).contains("4100", "1.000,00");
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Penjualan", "Net profit", "2026-01-01 to 2026-03-31");

		zk.click(nthButton("Export CSV", 2));
		List<String> file = zk.download();
		assertThat(file.get(0)).contains("income-statement-2026-01-01-2026-03-31.csv");
		assertThat(file.get(1)).contains("Revenue,4100,Penjualan,0,1000.00");
	}

	@Test
	void aBalanceSheetRunShowsTheDifference() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(TODAY, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		BigDecimal zero = BigDecimal.ZERO.setScale(2);
		when(api.balanceSheet(eq(TODAY))).thenReturn(new BalanceSheetDto(LocalDate.of(2026, 6, 30),
				List.of(new StatementRowDto(1L, "1290", "Akumulasi", 0, new BigDecimal("-5.00"))), List.of(), List.of(), new BigDecimal("-5.00"),
				new BigDecimal("-5.00"), zero, new BigDecimal("-5.00"), new BigDecimal("-5.00"), zero));
		page("reports.zul");

		zk.click(nthButton("Run", 3));

		assertThat(zk.all(Listcell.class)).extracting(Listcell::getLabel).contains("-5,00", "0,00");
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Earnings not yet closed", "Difference", "As of 2026-06-30");
	}

	/** A browser sends null for an emptied date box; the text "" would be refused by the box itself. */
	private void clear(Datebox box) throws Exception {
		zk.au("onChange", box.getUuid(), "{\"value\":null,\"start\":0}");
	}

	@Test
	void aClearedReportDateWarnsAndCallsNoApi() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(TODAY, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		page("reports.zul");
		List<Datebox> dates = zk.all(Datebox.class);

		clear(dates.get(3));
		zk.click(nthButton("Run", 2));
		assertThat(zk.response).contains("Choose a date range");
		verify(api, never()).incomeStatement(any(), any());

		clear(dates.get(5));
		zk.click(nthButton("Run", 3));
		assertThat(zk.response).contains("Choose a date");
		verify(api, never()).balanceSheet(any());
	}

	@Test
	void aRefusedStatementRunSaysWhyAndLeavesNothingToExport() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(TODAY, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		when(api.incomeStatement(any(), any())).thenThrow(problem(HttpStatus.BAD_REQUEST, "validation", "from must not be after to"));
		page("reports.zul");

		zk.click(nthButton("Run", 2));

		assertThat(zk.response).contains("from must not be after to");
		assertThat(nthButton("Export CSV", 2).isDisabled()).isTrue();
	}

	@Test
	void anUnmatchedJournalAccountWarnsAndDoesNotSearch() throws Exception {
		page("journal.zul");
		Combobox accountBox = zk.all(Combobox.class).get(1);
		verify(api, times(1)).entries(any());

		zk.change(accountBox, "nothing like this");
		zk.click(zk.button("Search"));
		assertThat(zk.response).contains("No single account matches");
		verify(api, times(1)).entries(any());

		zk.change(accountBox, "kas");
		zk.click(zk.button("Search"));
		verify(api, times(2)).entries(any());
		assertThat(accountBox.getValue()).isEqualTo(KAS.getLabel());
	}

	@Test
	void aRefusedSearchKeepsTheFilterTheListWasLoadedWith() throws Exception {
		when(api.entries(any())).thenReturn(new EntryPageDto(List.of(new EntrySummaryDto(7L, null, LocalDate.of(2026, 3, 15), "R",
				"Penjualan", "DRAFT", new BigDecimal("100.00"))), 120, 0, 50));
		page("journal.zul");
		Combobox accountBox = zk.all(Combobox.class).get(1);
		zk.change(accountBox, "kas");
		zk.click(zk.button("Search"));

		zk.change(accountBox, "nothing like this");
		zk.click(zk.button("Search"));
		zk.au("onPaging", zk.all(Paging.class).get(0).getUuid(), "{\"\":1}");

		ArgumentCaptor<EntryFilter> filter = ArgumentCaptor.forClass(EntryFilter.class);
		verify(api, times(3)).entries(filter.capture());
		assertThat(filter.getValue().page()).isEqualTo(1);
		assertThat(filter.getValue().accountId()).isEqualTo(KAS.id());
	}

	@Test
	void setupPanelsBeforeAndAfterSetup() throws Exception {
		when(api.settings()).thenReturn(new SettingsDto(false, true));
		page("accounts.zul");
		Button initialize = zk.button("Initialize from template");
		Checkbox separatePoster = zk.all(Checkbox.class).stream().filter(box -> box.getLabel() != null).findFirst().orElseThrow();
		assertThat(shown(initialize)).isTrue();
		assertThat(shown(separatePoster)).isFalse();

		when(api.settings()).thenReturn(new SettingsDto(true, true));
		zk.click(initialize);

		verify(api).setup();
		assertThat(shown(initialize)).isFalse();
		assertThat(shown(separatePoster)).isTrue();
	}

	@Test
	void aFailedLoadDoesNotOfferSetup() throws Exception {
		when(api.settings()).thenThrow(new IllegalStateException("backend down"));

		page("accounts.zul");

		assertThat(shown(zk.button("Initialize from template"))).isFalse();
	}

	@Test
	void theJournalExplainsTheFirstDayByPermission() throws Exception {
		when(api.settings()).thenReturn(new SettingsDto(false, true));
		when(api.entries(any())).thenReturn(new EntryPageDto(List.of(), 0, 0, 50));

		page("journal.zul");
		assertThat(zk.all(Label.class)).filteredOn(AccountingClickPathTests::shown).extracting(Label::getValue)
				.contains("Accounting is not set up yet. Open Accounts and choose Initialize from template.");
		assertThat(shown(zk.button("New entry"))).isFalse();

		can("accounting:read", "accounting:write");
		page("journal.zul");
		assertThat(zk.all(Label.class)).filteredOn(AccountingClickPathTests::shown).extracting(Label::getValue)
				.contains("Accounting is not set up yet. Ask your administrator to set it up.");
	}

	@Test
	void theJournalHidesTheBannerOnceSetUp() throws Exception {
		page("journal.zul");

		assertThat(zk.all(Label.class)).filteredOn(AccountingClickPathTests::shown).extracting(Label::getValue)
				.noneMatch(text -> text.contains("not set up"));
		assertThat(shown(zk.button("New entry"))).isTrue();
	}

	@Test
	void anOpenPeriodOffersCloseAndAClosedOneReopen() throws Exception {
		when(api.periods(anyInt())).thenReturn(List.of(
				new PeriodDto(3L, 2026, 3, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), "OPEN", null),
				new PeriodDto(4L, 2026, 4, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "CLOSED", "Ani")));
		page("periods.zul");

		List<Listitem> rows = zk.all(Listitem.class);
		assertThat(rowButtons(rows.get(0))).containsExactly("Close");
		assertThat(rowButtons(rows.get(1))).containsExactly("Reopen");

		can("accounting:read");
		page("periods.zul");
		assertThat(rowButtons(zk.all(Listitem.class).get(0))).isEmpty();
	}

	private static List<String> rowButtons(Listitem row) {
		return row.getChildren().stream().flatMap(cell -> cell.getChildren().stream()).filter(AccountingClickPathTests::shown)
				.map(button -> ((Button) button).getLabel()).toList();
	}

	@Test
	void openingAYearAsksFirst() throws Exception {
		when(api.periods(anyInt())).thenReturn(List.of(new PeriodDto(3L, 2026, 3, LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 3, 31), "OPEN", null)));
		page("periods.zul");
		zk.change(zk.all(Intbox.class).get(0), "2026");

		zk.click(zk.button("Open year 2027"));
		assertThat(dialogText()).isEqualTo("Open the 12 months of 2027? A year cannot be removed.");
		verify(api, never()).openYear(anyInt());

		zk.click(zk.button("Yes"));
		verify(api).openYear(2027);
	}

	@Test
	void aCloseRefusedWithNoDraftsToListShowsTheProblem() throws Exception {
		when(api.periods(anyInt())).thenReturn(List.of(new PeriodDto(3L, 2026, 3, LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 3, 31), "OPEN", null)));
		when(api.closePeriod(3L)).thenThrow(problem(HttpStatus.CONFLICT, "period-has-drafts", "This period still has drafts"));
		when(api.entries(any())).thenReturn(new EntryPageDto(List.of(), 0, 0, 50));
		page("periods.zul");

		zk.click(zk.button("Close"));

		assertThat(zk.response).contains("This period still has drafts");
	}

	private static final AccountDto RETAINED = new AccountDto(9L, "3200", "Laba Ditahan", "EQUITY", null, true, true);

	private static final AccountDto CAPITAL = new AccountDto(10L, "3100", "Modal", "EQUITY", null, true, true);

	private static final int YEAR = TODAY.getYear();

	private void aYear(boolean closed, String net) {
		when(api.periods(anyInt())).thenReturn(List.of(new PeriodDto(3L, YEAR, 3, LocalDate.of(YEAR, 3, 1), LocalDate.of(YEAR, 3, 31),
				closed ? "CLOSED" : "OPEN", null)));
		when(api.yearStatus(anyInt())).thenReturn(new YearStatusDto(YEAR, closed, closed ? 9L : null, closed ? "JE-1" : null));
		when(api.accounts()).thenReturn(List.of(KAS, CAPITAL, RETAINED));
		when(api.incomeStatement(eq(LocalDate.of(YEAR, 1, 1)), eq(LocalDate.of(YEAR, 12, 31)))).thenReturn(new IncomeStatementDto(
				LocalDate.of(YEAR, 1, 1), LocalDate.of(YEAR, 12, 31), List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(net)));
	}

	private Textbox shownTextbox() {
		return zk.all(Textbox.class).stream().filter(AccountingClickPathTests::shown).filter(box -> !(box instanceof Combobox)).findFirst().orElseThrow();
	}

	@Test
	void closingAYearStatesTheNetDefaultsTheAccountAndAsksFirst() throws Exception {
		aYear(false, "700.00");
		page("periods.zul");
		assertThat(shown(zk.button("Close year"))).isTrue();
		assertThat(shown(zk.button("Reopen year"))).isFalse();

		zk.click(zk.button("Close year"));
		verify(api, never()).closeYear(anyInt(), anyLong());
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Close " + YEAR + ": net profit 700,00 moves to the account below");
		Combobox account = zk.all(Combobox.class).stream().filter(AccountingClickPathTests::shown).findFirst().orElseThrow();
		assertThat(account.getValue()).isEqualTo("3200 Laba Ditahan");

		zk.click(zk.button("Confirm close"));
		verify(api).closeYear(YEAR, 9L);
		assertThat(shown(zk.button("Confirm close"))).isFalse();
	}

	@Test
	void aLossIsStatedAsALossAndAnotherAccountCanBeChosen() throws Exception {
		aYear(false, "-200.00");
		page("periods.zul");

		zk.click(zk.button("Close year"));
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Close " + YEAR + ": net loss 200,00 moves to the account below");
		Combobox account = zk.all(Combobox.class).stream().filter(AccountingClickPathTests::shown).findFirst().orElseThrow();
		assertThat(account.getModel().getSize()).as("only equity accounts are offered").isEqualTo(2);

		zk.change(account, CAPITAL.getLabel());
		zk.click(zk.button("Confirm close"));
		verify(api).closeYear(YEAR, 10L);
	}

	@Test
	void anAccountThatMatchesNothingWarnsAndDoesNotClose() throws Exception {
		aYear(false, "700.00");
		page("periods.zul");
		zk.click(zk.button("Close year"));

		zk.change(zk.all(Combobox.class).stream().filter(AccountingClickPathTests::shown).findFirst().orElseThrow(), "nope");
		zk.click(zk.button("Confirm close"));

		assertThat(zk.response).contains("Choose an equity account");
		verify(api, never()).closeYear(anyInt(), anyLong());
	}

	@Test
	void aRefusedCloseShowsTheProblemAndKeepsThePanel() throws Exception {
		aYear(false, "700.00");
		when(api.closeYear(anyInt(), anyLong())).thenThrow(problem(HttpStatus.CONFLICT, "year-has-drafts", "Post or delete the 2 draft entries"));
		page("periods.zul");
		zk.click(zk.button("Close year"));

		zk.click(zk.button("Confirm close"));

		assertThat(zk.response).contains("Post or delete the 2 draft entries");
		assertThat(shown(zk.button("Confirm close"))).isTrue();
	}

	@Test
	void changingTheYearDropsAnOpenClosePanel() throws Exception {
		aYear(false, "700.00");
		page("periods.zul");
		zk.click(zk.button("Close year"));
		assertThat(shown(zk.button("Confirm close"))).isTrue();

		zk.change(zk.all(Intbox.class).get(0), YEAR - 1);

		assertThat(shown(zk.button("Confirm close"))).isFalse();
		verify(api, never()).closeYear(anyInt(), anyLong());
	}

	@Test
	void aFailedNetLoadShowsTheProblemAndOpensNoPanel() throws Exception {
		aYear(false, "700.00");
		when(api.incomeStatement(any(), any())).thenThrow(problem(HttpStatus.BAD_REQUEST, "validation", "Reports are down"));
		page("periods.zul");

		zk.click(zk.button("Close year"));

		assertThat(zk.response).contains("Reports are down");
		assertThat(shown(zk.button("Confirm close"))).isFalse();
	}

	@Test
	void reopeningAClosedYearNeedsAReasonAndHidesMonthReopen() throws Exception {
		aYear(true, "700.00");
		page("periods.zul");
		assertThat(shown(zk.button("Close year"))).isFalse();
		assertThat(rowButtons(zk.all(Listitem.class).get(0))).isEmpty();
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Closed by JE-1");

		zk.click(zk.button("Reopen year"));
		zk.click(zk.button("Confirm year reopen"));
		assertThat(zk.response).contains("Enter a reason");
		verify(api, never()).reopenYear(anyInt(), anyString());

		zk.change(shownTextbox(), "late invoice");
		zk.click(zk.button("Confirm year reopen"));
		verify(api).reopenYear(YEAR, "late invoice");
	}

	@Test
	void withoutThePermissionNoYearActionIsOffered() throws Exception {
		aYear(false, "700.00");
		can("accounting:read");
		page("periods.zul");

		assertThat(shown(zk.button("Close year"))).isFalse();
		assertThat(shown(zk.button("Reopen year"))).isFalse();
	}

	@Test
	void closingADirtyFormAsksAndACleanOneDoesNot() throws Exception {
		newEntry();
		assertThat(zk.response).contains("Unsaved changes will be lost.");
		closeEntryWindow();
		assertThat(entryWindows()).isEmpty();

		newEntry();
		zk.change(memo(0), "work");
		closeEntryWindow();
		assertThat(entryWindows()).hasSize(1);
		assertThat(dialogText()).isEqualTo("Discard unsaved changes?");

		zk.click(zk.button("No"));
		assertThat(entryWindows()).hasSize(1);

		closeEntryWindow();
		zk.click(zk.button("Yes"));
		assertThat(entryWindows()).isEmpty();
	}

	@Test
	void anEntryThatFailsToLoadClosesItsWindowWithoutAsking() throws Exception {
		when(api.entry(7L)).thenThrow(problem(HttpStatus.NOT_FOUND, "not-found", "No such entry"));
		page("journal.zul");

		zk.click(zk.all(Listitem.class).get(0));

		assertThat(entryWindows()).isEmpty();
		assertThat(zk.response).contains("No such entry");
	}

	@Test
	void anExpiredLoginWhileOpeningAnEntryLeavesWithoutTheLeaveWarning() throws Exception {
		when(api.entry(7L)).thenThrow(new OAuth2AuthorizationException(new OAuth2Error("invalid_grant")));
		page("journal.zul");

		zk.click(zk.all(Listitem.class).get(0));

		assertThat(zk.response).contains("/logout").doesNotContain("Unsaved changes will be lost.");
	}

	@Test
	void aFailedTokenRefreshOnSaveSaysSoBeforeLeaving() throws Exception {
		newEntry();
		zk.change(description(), "work");
		when(api.createEntry(any())).thenThrow(new OAuth2AuthorizationException(new OAuth2Error("invalid_grant")));

		zk.click(zk.button("Save draft"));
		assertThat(dialogText()).isEqualTo("Your login has expired. This entry was not saved.");
		assertThat(zk.response).doesNotContain("/logout");

		zk.click(zk.button("OK"));
		assertThat(zk.response).contains("/logout");
	}

	@Test
	void aForbiddenPostShowsTheProblemsWordsEscaped() throws Exception {
		openEntry(entry(7L, null, "DRAFT", 3));
		when(api.postEntry(7L, 3L)).thenThrow(problem(HttpStatus.FORBIDDEN, "separate-poster-required", "You prepared <img src=x>"));

		zk.click(zk.button("Post"));

		assertThat(zk.response).contains("You prepared &lt;img src=x&gt;").doesNotContain("<img src=x>");
	}

	private static AuditPageDto auditPage(long total) {
		return new AuditPageDto(List.of(new AuditRowDto(Instant.parse("2026-03-14T17:30:00Z"), "Akuntan", "ACCOUNT_CHANGE",
				"account", 5L, Map.of())), total, 0, 50);
	}

	private void stubAudit(long total) {
		when(api.audit(any(), any(), any(), anyInt(), anyInt())).thenAnswer(call -> {
			zk.captureDesktop();
			return auditPage(total);
		});
	}

	@Test
	void auditFiltersAndKeepsTheFilterWhilePaging() throws Exception {
		stubAudit(120);
		page("audit.zul");
		verify(api).audit(isNull(), isNull(), eq(""), eq(0), eq(50));

		zk.change(zk.all(Combobox.class).get(0), "POST");
		zk.click(zk.button("Search"));
		verify(api).audit(isNull(), isNull(), eq("POST"), eq(0), eq(50));

		zk.au("onPaging", zk.all(Paging.class).get(0).getUuid(), "{\"\":1}");
		verify(api).audit(isNull(), isNull(), eq("POST"), eq(1), eq(50));
	}

	@Test
	void aRefusedAuditSearchEmptiesTheListAndSaysWhy() throws Exception {
		stubAudit(1);
		page("audit.zul");
		assertThat(zk.all(Listitem.class)).hasSize(1);

		when(api.audit(any(), any(), any(), anyInt(), anyInt())).thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN,
				"Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
		zk.click(zk.button("Search"));

		assertThat(zk.all(Listitem.class)).isEmpty();
		assertThat(zk.response).contains("have permission to do that");
	}

}
