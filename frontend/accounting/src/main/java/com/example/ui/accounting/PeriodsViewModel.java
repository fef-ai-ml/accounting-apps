package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.zkoss.bind.BindUtils;
import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.GlobalCommand;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Messagebox;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind periods.zul: a year's months, close and reopen of a month and of the whole year. */
@VariableResolver(SpringBeanVariableResolver.class)
public class PeriodsViewModel {

	@WireVariable
	private AccountingApiClient accountingApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private Integer year = LocalDate.now(ZoneId.of("Asia/Jakarta")).getYear();

	private boolean canManage;

	private List<PeriodDto> periods = List.of();

	private List<EntrySummaryDto> blockingDrafts = List.of();

	private PeriodDto blockedPeriod;

	private PeriodDto reopening;

	private String reason = "";

	private YearStatusDto yearStatus;

	private boolean closingYear;

	private BigDecimal closeNet;

	private List<AccountDto> equityAccounts = List.of();

	private String retainedEarningsText = "";

	private boolean reopeningYear;

	@Init
	public void init() {
		try {
			canManage = currentUser.can("accounting:period-manage");
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		fetch();
	}

	@Command
	@NotifyChange("*")
	public void changeYear(@BindingParam("year") Integer year) {
		this.year = year;
		load();
	}

	private void load() {
		blockingDrafts = List.of();
		blockedPeriod = null;
		reopening = null;
		closingYear = false;
		reopeningYear = false;
		fetch();
	}

	/** Asks first: the API offers no way to remove a year opened by mistake. */
	@Command
	public void openYear() {
		if (year == null) {
			return;
		}
		int target = nextYear();
		Messagebox.show("Open the 12 months of " + target + "? A year cannot be removed.", "Open year", Messagebox.YES | Messagebox.NO,
				Messagebox.QUESTION, (Event event) -> {
					if (Messagebox.ON_YES.equals(event.getName())) {
						try {
							accountingApiClient.openYear(target);
							year = target;
						}
						catch (RuntimeException ex) {
							ApiErrors.show(ex);
						}
						load();
						BindUtils.postNotifyChange(this, "*");
					}
				});
	}

	@Command
	@NotifyChange("*")
	public void close(@BindingParam("period") PeriodDto period) {
		try {
			accountingApiClient.closePeriod(period.id());
			load();
		}
		catch (RuntimeException ex) {
			if ("period-has-drafts".equals(ApiErrors.code(ex))) {
				showBlockingDrafts(period);
				// The list can come back empty, say when another user just dealt with them.
				if (blockingDrafts.isEmpty()) {
					ApiErrors.show(ex);
				}
				return;
			}
			ApiErrors.show(ex);
		}
	}

	@Command
	public void openDraft(@BindingParam("id") Long id) {
		EntryViewModel.open(id);
	}

	/** A draft posted or deleted from the modal may unblock the close. */
	@GlobalCommand("accountingEntryChanged")
	@NotifyChange("*")
	public void entryChanged() {
		if (blockedPeriod != null) {
			showBlockingDrafts(blockedPeriod);
		}
	}

	@Command
	@NotifyChange("*")
	public void startReopen(@BindingParam("period") PeriodDto period) {
		reopening = period;
		reason = "";
		closingYear = false;
		reopeningYear = false;
	}

	@Command
	@NotifyChange("*")
	public void cancelReopen() {
		reopening = null;
	}

	@Command
	@NotifyChange("*")
	public void confirmReopen() {
		if (reason == null || reason.isBlank()) {
			Clients.showNotification("Enter a reason", "warning", null, "middle_center", 3000);
			return;
		}
		try {
			accountingApiClient.reopenPeriod(reopening.id(), reason.strip());
			load();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	/** Shows what the close will move before anything happens. */
	@Command
	@NotifyChange("*")
	public void startCloseYear() {
		try {
			closeNet = accountingApiClient.incomeStatement(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).netProfit();
			equityAccounts = accountingApiClient.accounts().stream()
					.filter(account -> "EQUITY".equals(account.type()) && account.postable() && account.active())
					.toList();
			retainedEarningsText = equityAccounts.stream().filter(account -> "3200".equals(account.code())).findFirst()
					.map(AccountDto::getLabel).orElse("");
			closingYear = true;
			reopening = null;
			reopeningYear = false;
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	@Command
	@NotifyChange("*")
	public void cancelCloseYear() {
		closingYear = false;
	}

	@Command
	@NotifyChange("*")
	public void confirmCloseYear() {
		AccountDto account = AccountMatcher.find(retainedEarningsText, equityAccounts).orElse(null);
		if (account == null) {
			Clients.showNotification("Choose an equity account", "warning", null, "middle_center", 3000);
			return;
		}
		try {
			accountingApiClient.closeYear(year, account.id());
			load();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	@Command
	@NotifyChange("*")
	public void startReopenYear() {
		reopeningYear = true;
		reason = "";
		reopening = null;
		closingYear = false;
	}

	@Command
	@NotifyChange("*")
	public void cancelReopenYear() {
		reopeningYear = false;
	}

	@Command
	@NotifyChange("*")
	public void confirmReopenYear() {
		if (reason == null || reason.isBlank()) {
			Clients.showNotification("Enter a reason", "warning", null, "middle_center", 3000);
			return;
		}
		try {
			accountingApiClient.reopenYear(year, reason.strip());
			load();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	private void showBlockingDrafts(PeriodDto period) {
		blockedPeriod = period;
		try {
			blockingDrafts = accountingApiClient.entries(new EntryFilter(period.startDate(), period.endDate(), "DRAFT", null, null,
					0, JournalViewModel.PAGE_SIZE)).items();
		}
		catch (RuntimeException ex) {
			blockingDrafts = List.of();
			ApiErrors.show(ex);
		}
	}

	private void fetch() {
		yearStatus = null;
		if (year == null) {
			periods = List.of();
			return;
		}
		try {
			periods = accountingApiClient.periods(year);
			yearStatus = periods.isEmpty() ? null : accountingApiClient.yearStatus(year);
		}
		catch (RuntimeException ex) {
			periods = List.of();
			ApiErrors.show(ex);
		}
	}

	/** The year on screen when it has no periods, so a skipped year can be opened; otherwise the following one. */
	private int nextYear() {
		return periods.isEmpty() ? year : year + 1;
	}

	public Integer getYear() {
		return year;
	}

	public String getOpenYearLabel() {
		return year == null ? "Open year" : "Open year " + nextYear();
	}

	public boolean isCanManage() {
		return canManage;
	}

	public List<PeriodDto> getPeriods() {
		return periods;
	}

	public List<EntrySummaryDto> getBlockingDrafts() {
		return blockingDrafts;
	}

	public String getBlockedText() {
		return blockedPeriod == null ? "" : "Post or delete these drafts to close " + blockedPeriod.getLabel();
	}

	public PeriodDto getReopening() {
		return reopening;
	}

	public String getReopenText() {
		return reopening == null ? "" : "Reopen " + reopening.getLabel();
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public boolean isYearClosed() {
		return yearStatus != null && yearStatus.closed();
	}

	public String getYearStatusText() {
		return isYearClosed() ? "Closed by " + yearStatus.entryNo() : "";
	}

	public boolean isCanCloseYear() {
		return canManage && !periods.isEmpty() && !isYearClosed();
	}

	public boolean isCanReopenYear() {
		return canManage && isYearClosed();
	}

	public boolean isClosingYear() {
		return closingYear;
	}

	public String getCloseYearText() {
		if (!closingYear) {
			return "";
		}
		String net = closeNet.signum() < 0 ? "net loss " : "net profit ";
		return "Close " + year + ": " + net + Idr.format(closeNet.abs()) + " moves to the account below";
	}

	public List<AccountDto> getEquityAccounts() {
		return equityAccounts;
	}

	public String getRetainedEarningsText() {
		return retainedEarningsText;
	}

	public void setRetainedEarningsText(String retainedEarningsText) {
		this.retainedEarningsText = retainedEarningsText;
	}

	public boolean isReopeningYear() {
		return reopeningYear;
	}

	public String getReopenYearText() {
		return reopeningYear ? "Reopen " + year + ": this reverses its closing entry and opens December" : "";
	}

}
