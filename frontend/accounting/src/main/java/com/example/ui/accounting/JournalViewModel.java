package com.example.ui.accounting;

import java.time.LocalDate;
import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.GlobalCommand;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zul.ListModel;
import org.zkoss.zul.ListModelList;
import org.zkoss.zul.ListModels;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind journal.zul: the filtered, server-side paged list. */
@VariableResolver(SpringBeanVariableResolver.class)
public class JournalViewModel {

	static final int PAGE_SIZE = 50;

	private static final int ACCOUNT_CHOICES = 50;

	@WireVariable
	private AccountingApiClient accountingApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private LocalDate from;

	private LocalDate to;

	private String status = "";

	private String accountText = "";

	private String q = "";

	private int page;

	private long total;

	private boolean canWrite;

	private boolean canManageAccounts;

	/** True until the backend says otherwise: a failed load must not claim accounting is not set up. */
	private boolean initialized = true;

	private Long accountId;

	private List<EntrySummaryDto> entries = List.of();

	private List<AccountDto> accounts = List.of();

	private ListModel<Object> accountModel = new ListModelList<>();

	@Init
	public void init() {
		try {
			canWrite = currentUser.can("accounting:write");
			canManageAccounts = currentUser.can("accounting:account-manage");
			initialized = accountingApiClient.settings().initialized();
			accounts = accountingApiClient.accounts();
			accountModel = ListModels.toListSubModel(new ListModelList<Object>(accounts), new AccountMatcher(), ACCOUNT_CHOICES);
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
	}

	@Command
	@NotifyChange({ "entries", "total", "page", "accountText" })
	public void search() {
		Long matched = null;
		if (accountText != null && !accountText.isBlank()) {
			AccountDto account = AccountMatcher.find(accountText, accounts).orElse(null);
			if (account == null) {
				EntryViewModel.warnNoMatch(accountText);
				return;
			}
			matched = account.id();
			accountText = account.getLabel();
		}
		accountId = matched;
		page = 0;
		load();
	}

	@Command
	@NotifyChange({ "entries", "total", "page" })
	public void goToPage(@BindingParam("page") int page) {
		this.page = page;
		load();
	}

	@GlobalCommand("accountingEntryChanged")
	@NotifyChange({ "entries", "total", "page" })
	public void reload() {
		load();
	}

	@Command
	public void newEntry() {
		EntryViewModel.open(null);
	}

	@Command
	public void open(@BindingParam("id") Long id) {
		EntryViewModel.open(id);
	}

	private void load() {
		try {
			EntryPageDto result = accountingApiClient.entries(new EntryFilter(from, to, status, accountId, q, page, PAGE_SIZE));
			if (result.items().isEmpty() && page > 0) {
				page = (int) Math.max(0, (result.total() - 1) / PAGE_SIZE);
				result = accountingApiClient.entries(new EntryFilter(from, to, status, accountId, q, page, PAGE_SIZE));
			}
			entries = result.items();
			total = result.total();
		}
		catch (RuntimeException ex) {
			page = 0;
			entries = List.of();
			total = 0;
			ApiErrors.show(ex);
		}
	}

	public LocalDate getFrom() {
		return from;
	}

	public void setFrom(LocalDate from) {
		this.from = from;
	}

	public LocalDate getTo() {
		return to;
	}

	public void setTo(LocalDate to) {
		this.to = to;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public List<String> getStatuses() {
		return List.of("", "DRAFT", "POSTED", "REVERSED");
	}

	public String getAccountText() {
		return accountText;
	}

	public void setAccountText(String accountText) {
		this.accountText = accountText;
	}

	public ListModel<Object> getAccountModel() {
		return accountModel;
	}

	public String getQ() {
		return q;
	}

	public void setQ(String q) {
		this.q = q;
	}

	public int getPage() {
		return page;
	}

	public int getPageSize() {
		return PAGE_SIZE;
	}

	public int getTotal() {
		return (int) total;
	}

	public boolean isCanWrite() {
		return canWrite;
	}

	public boolean isInitialized() {
		return initialized;
	}

	public String getSetupText() {
		return "Accounting is not set up yet. "
				+ (canManageAccounts ? "Open Accounts and choose Initialize from template." : "Ask your administrator to set it up.");
	}

	public List<EntrySummaryDto> getEntries() {
		return entries;
	}

}
