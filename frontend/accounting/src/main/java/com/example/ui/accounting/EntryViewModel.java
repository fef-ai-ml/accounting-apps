package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.web.client.HttpClientErrorException;
import org.zkoss.bind.BindUtils;
import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.ContextParam;
import org.zkoss.bind.annotation.ContextType;
import org.zkoss.bind.annotation.ExecutionArgParam;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.xml.XMLs;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.ListModel;
import org.zkoss.zul.ListModelList;
import org.zkoss.zul.ListModels;
import org.zkoss.zul.Messagebox;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind entry.zul, a modal so any screen can open an entry. Rules live in {@link EntryForm}. */
@VariableResolver(SpringBeanVariableResolver.class)
public class EntryViewModel {

	private static final Clock JAKARTA = Clock.system(ZoneId.of("Asia/Jakarta"));

	private static final int ACCOUNT_CHOICES = 50;

	/** Must match the window's id in entry.zul. */
	private static final String WINDOW_ID = "accountingEntryWindow";

	/** Errors that mean someone else changed the entry, so the screen shows stale buttons. */
	private static final Set<String> STALE = Set.of("version-conflict", "not-draft", "already-reversed");

	@WireVariable
	private AccountingApiClient accountingApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private Long id;

	private EntryDto entry;

	private EntryForm form = EntryForm.blank(LocalDate.now(JAKARTA));

	private ListModelList<EntryLine> lines = new ListModelList<>();

	private String savedSnapshot = form.snapshot();

	private String periodText = "";

	private LocalDate reverseDate;

	private boolean canWrite;

	private boolean canPost;

	private List<AccountDto> options = List.of();

	private Map<Long, AccountDto> accountsById = Map.of();

	private Component view;

	/** Opens the entry in a modal window; {@code entryId} null starts a new draft. */
	public static void open(Long entryId) {
		// A double click on a row arrives as two commands.
		if (Executions.getCurrent().getDesktop().getPages().stream().anyMatch(page -> page.hasFellow(WINDOW_ID))) {
			return;
		}
		Map<String, Object> args = new HashMap<>();
		args.put("entryId", entryId);
		Executions.createComponents("~./accounting/entry.zul", null, args);
	}

	@Init
	public void init(@ExecutionArgParam("entryId") Long entryId, @ContextParam(ContextType.VIEW) Component view) {
		this.view = view;
		Clients.confirmClose("Unsaved changes will be lost.");
		try {
			canWrite = currentUser.can("accounting:write");
			canPost = currentUser.can("accounting:post");
			List<AccountDto> accounts = accountingApiClient.accounts();
			accountsById = accounts.stream().collect(Collectors.toMap(AccountDto::id, Function.identity()));
			options = accounts.stream().filter(account -> account.postable() && account.active()).toList();
			if (entryId != null) {
				show(accountingApiClient.entry(entryId));
				return;
			}
		}
		catch (RuntimeException ex) {
			// A blank form here could be mistaken for the entry that failed to load.
			Clients.confirmClose(null);
			ApiErrors.show(ex);
			Events.postEvent(Events.ON_CLOSE, view, null);
			return;
		}
		showForm();
		refreshPeriod();
	}

	/** The window's X. A dirty form asks; the event is stopped either way so the window has one way out, {@link #close}. */
	@Command
	public void closing(@BindingParam("event") Event closeEvent) {
		closeEvent.stopPropagation();
		if (!isDirty()) {
			close();
			return;
		}
		Messagebox.show("Discard unsaved changes?", "Close", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION, (Event event) -> {
			if (Messagebox.ON_YES.equals(event.getName())) {
				close();
			}
		});
	}

	private void close() {
		Clients.confirmClose(null);
		view.detach();
	}

	@Command
	@NotifyChange("periodText")
	public void setDate(@BindingParam("date") LocalDate date) {
		form.setDate(date);
		refreshPeriod();
	}

	@Command
	@NotifyChange({ "totalsText", "balanceText", "balanced" })
	public void addLine() {
		lines.add(new EntryLine());
	}

	@Command
	@NotifyChange({ "totalsText", "balanceText", "balanced" })
	public void removeLine(@BindingParam("line") EntryLine line) {
		lines.remove(line);
	}

	@Command
	@NotifyChange({ "totalsText", "balanceText", "balanced" })
	public void setDebit(@BindingParam("line") EntryLine line, @BindingParam("amount") BigDecimal amount) {
		line.setDebit(amount);
		BindUtils.postNotifyChange(line, "debit", "credit");
	}

	@Command
	@NotifyChange({ "totalsText", "balanceText", "balanced" })
	public void setCredit(@BindingParam("line") EntryLine line, @BindingParam("amount") BigDecimal amount) {
		line.setCredit(amount);
		BindUtils.postNotifyChange(line, "debit", "credit");
	}

	/** Picking on the last line appends a blank one: the only way into a new row with the keyboard. */
	@Command
	public void typedAccount(@BindingParam("line") EntryLine line, @BindingParam("text") String text) {
		boolean found = line.pickByLabel(text, options);
		BindUtils.postNotifyChange(line, "accountLabel");
		if (found && lines.indexOf(line) == lines.size() - 1) {
			lines.add(new EntryLine());
		}
		else if (!found && text != null && !text.isBlank()) {
			warnNoMatch(text);
		}
	}

	@Command
	@NotifyChange({ "totalsText", "balanceText", "balanced" })
	public void balance(@BindingParam("line") EntryLine line) {
		form.balance(line);
		BindUtils.postNotifyChange(line, "debit", "credit");
	}

	@Command
	@NotifyChange("*")
	public void save() {
		String problem = form.problem();
		if (problem != null) {
			warn(problem);
			return;
		}
		try {
			EntryRequest request = form.toRequest(entry == null ? null : entry.version());
			show(id == null ? accountingApiClient.createEntry(request) : accountingApiClient.updateEntry(id, request));
			entryChanged();
		}
		catch (RuntimeException ex) {
			handle(ex);
		}
	}

	@Command
	public void delete() {
		// The answer comes later; it must apply to what was on screen when the question was asked.
		long entryId = id;
		long version = entry.version();
		Messagebox.show("Delete this draft?", "Delete", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION, (Event event) -> {
			if (Messagebox.ON_YES.equals(event.getName())) {
				try {
					accountingApiClient.deleteEntry(entryId, version);
					entryChanged();
					close();
				}
				catch (RuntimeException ex) {
					handle(ex);
				}
			}
		});
	}

	@Command
	@NotifyChange("*")
	public void post() {
		// The backend posts the saved draft, so what is on screen must be what was saved.
		if (entry == null || isDirty()) {
			warn("Save the draft first: it has changes that are not saved");
			return;
		}
		try {
			show(accountingApiClient.postEntry(id, entry.version()));
			entryChanged();
		}
		catch (RuntimeException ex) {
			handle(ex);
		}
	}

	@Command
	public void reverse(@BindingParam("copy") boolean copy) {
		// The answer comes later; a Yes must reverse the entry it was asked about, not the reversal now on screen.
		long entryId = id;
		LocalDate date = reverseDate;
		Messagebox.show("Post a reversal of " + entry.entryNo() + " dated " + (date == null ? "today" : date)
				+ (copy ? " and copy it to a new draft" : "") + "? This cannot be undone.", "Reverse",
				Messagebox.YES | Messagebox.NO, Messagebox.QUESTION, (Event event) -> {
					if (Messagebox.ON_YES.equals(event.getName())) {
						try {
							ReverseResultDto result = accountingApiClient.reverseEntry(entryId, date, copy);
							entryChanged();
							show(accountingApiClient.entry(copy ? result.draftId() : result.reversal().id()));
						}
						catch (RuntimeException ex) {
							handle(ex);
						}
						BindUtils.postNotifyChange(this, "*");
					}
				});
	}

	@Command
	@NotifyChange("*")
	public void openLinked() {
		Long linked = entry.reversedBy() != null ? entry.reversedBy() : entry.reversalOf();
		try {
			show(accountingApiClient.entry(linked));
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	private void handle(RuntimeException ex) {
		if (ApiErrors.classify(ex) == ApiErrors.Kind.REAUTHENTICATE) {
			Messagebox.show("Your login has expired. This entry was not saved.", "Login expired", Messagebox.OK, Messagebox.EXCLAMATION,
					(Event event) -> {
						Clients.confirmClose(null);
						ApiErrors.show(ex);
					});
			return;
		}
		if (STALE.contains(ApiErrors.code(ex))) {
			Messagebox.show("Someone else changed this entry. Reload?", "Changed", Messagebox.YES | Messagebox.NO,
					Messagebox.QUESTION, (Event event) -> {
						if (Messagebox.ON_YES.equals(event.getName())) {
							reload();
						}
					});
			return;
		}
		ApiErrors.show(ex);
	}

	private void reload() {
		try {
			show(accountingApiClient.entry(id));
			entryChanged();
			BindUtils.postNotifyChange(this, "*");
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
			if (ex instanceof HttpClientErrorException.NotFound) {
				entryChanged();
				Events.postEvent(Events.ON_CLOSE, view, null);
			}
		}
	}

	private void show(EntryDto loaded) {
		entry = loaded;
		id = loaded.id();
		form = EntryForm.from(loaded, accountsById);
		reverseDate = LocalDate.now(JAKARTA);
		showForm();
		refreshPeriod();
	}

	private void showForm() {
		lines = new ListModelList<>(form.getLines(), true);
		savedSnapshot = form.snapshot();
	}

	private void refreshPeriod() {
		LocalDate date = form.getDate();
		if (date == null) {
			periodText = "";
			return;
		}
		try {
			periodText = accountingApiClient.periods(date.getYear()).stream()
					.filter(period -> period.month() == date.getMonthValue())
					.map(period -> period.getLabel() + " · " + period.status())
					.findFirst().orElse("No period for this date");
		}
		catch (RuntimeException ex) {
			periodText = "";
			ApiErrors.show(ex);
		}
	}

	private static void entryChanged() {
		BindUtils.postGlobalCommand(null, null, "accountingEntryChanged", Collections.emptyMap());
	}

	private static void warn(String message) {
		Clients.showNotification(message, "warning", null, "middle_center", 4000);
	}

	/** Notifications render HTML, and this one quotes what was typed. */
	static void warnNoMatch(String text) {
		warn("No single account matches \"" + XMLs.escapeXML(text.strip()) + "\"");
	}

	private boolean isDirty() {
		return !form.snapshot().equals(savedSnapshot);
	}

	public String getTitle() {
		if (entry == null) {
			return "New journal entry";
		}
		return (entry.entryNo() == null ? "Draft entry" : entry.entryNo()) + " · " + entry.status();
	}

	public EntryForm getForm() {
		return form;
	}

	public ListModelList<EntryLine> getLines() {
		return lines;
	}

	/** One model per combobox: sub-models share their selection with the model behind them. */
	public ListModel<Object> getNewAccountModel() {
		return accountModel(options);
	}

	static ListModel<Object> accountModel(List<AccountDto> options) {
		return ListModels.toListSubModel(new ListModelList<Object>(options), new AccountMatcher(), ACCOUNT_CHOICES);
	}

	public String getPeriodText() {
		return periodText;
	}

	public String getTotalsText() {
		return "Debit " + Idr.format(form.totalDebit()) + " · Credit " + Idr.format(form.totalCredit());
	}

	public String getBalanceText() {
		if (form.isBalanced()) {
			return "Balanced";
		}
		// Not balanced with no difference means nothing has been entered yet.
		return form.difference().signum() == 0 ? "" : "Difference " + Idr.format(form.difference().abs());
	}

	public boolean isBalanced() {
		return form.isBalanced();
	}

	public boolean isSaved() {
		return id != null;
	}

	public boolean isDraft() {
		return entry == null || "DRAFT".equals(entry.status());
	}

	public boolean isPosted() {
		return entry != null && "POSTED".equals(entry.status());
	}

	public boolean isClosingEntry() {
		return entry != null && entry.closingYear() != null;
	}

	/** Only reopening its year undoes a closing entry. */
	public boolean isReversible() {
		return isPosted() && !isClosingEntry();
	}

	public boolean isReadOnly() {
		return !isDraft() || !canWrite;
	}

	public boolean isCanWrite() {
		return canWrite;
	}

	public boolean isCanPost() {
		return canPost;
	}

	public boolean isPosterBlocked() {
		return entry != null && entry.posterBlocked();
	}

	public String getLinkText() {
		if (entry != null && entry.reversedBy() != null) {
			return "Open the reversal";
		}
		return entry != null && entry.reversalOf() != null ? "Open the reversed entry" : "";
	}

	public LocalDate getReverseDate() {
		return reverseDate;
	}

	public void setReverseDate(LocalDate reverseDate) {
		this.reverseDate = reverseDate;
	}

}
