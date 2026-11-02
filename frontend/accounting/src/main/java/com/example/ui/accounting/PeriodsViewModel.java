package com.example.ui.accounting;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.GlobalCommand;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind periods.zul: a year's months, close (with blocking drafts) and reopen (with a reason). */
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
		fetch();
	}

	@Command
	@NotifyChange("*")
	public void openYear() {
		try {
			accountingApiClient.openYear(nextYear());
			year = nextYear();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
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
		if (year == null) {
			periods = List.of();
			return;
		}
		try {
			periods = accountingApiClient.periods(year);
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

}
