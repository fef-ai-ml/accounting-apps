package com.example.ui.accounting;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Filedownload;
import org.zkoss.zul.ListModel;
import org.zkoss.zul.ListModelList;
import org.zkoss.zul.ListModels;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind reports.zul: trial balance and general ledger, both exportable. */
@VariableResolver(SpringBeanVariableResolver.class)
public class ReportsViewModel {

	private static final String CSV = "text/csv";

	private static final int ACCOUNT_CHOICES = 50;

	@WireVariable
	private AccountingApiClient accountingApiClient;

	private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));

	private LocalDate asOf = today;

	private TrialBalanceDto trial;

	private List<AccountDto> accounts = List.of();

	private ListModel<Object> accountModel = new ListModelList<>();

	private String ledgerAccountText = "";

	private LocalDate ledgerFrom = today.withDayOfYear(1);

	private LocalDate ledgerTo = today;

	private GeneralLedgerDto ledger;

	@Init
	public void init() {
		try {
			accounts = accountingApiClient.accounts();
			accountModel = ListModels.toListSubModel(new ListModelList<Object>(accounts), new AccountMatcher(), ACCOUNT_CHOICES);
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		loadTrialBalance();
	}

	@Command
	@NotifyChange("*")
	public void runTrialBalance() {
		loadTrialBalance();
	}

	@Command
	public void exportTrialBalance() {
		Filedownload.save(Csv.trialBalance(trial), CSV, "trial-balance-" + trial.asOf() + ".csv");
	}

	@Command
	@NotifyChange("*")
	public void runLedger() {
		AccountDto account = AccountMatcher.find(ledgerAccountText, accounts).orElse(null);
		if (account == null || ledgerFrom == null || ledgerTo == null) {
			Clients.showNotification("Choose an account and a date range", "warning", null, "middle_center", 3000);
			return;
		}
		ledgerAccountText = account.getLabel();
		try {
			ledger = accountingApiClient.generalLedger(account.id(), ledgerFrom, ledgerTo);
		}
		catch (RuntimeException ex) {
			ledger = null;
			ApiErrors.show(ex);
		}
	}

	@Command
	public void exportLedger() {
		Filedownload.save(Csv.generalLedger(ledger), CSV, Csv.generalLedgerFileName(ledger));
	}

	private void loadTrialBalance() {
		try {
			trial = accountingApiClient.trialBalance(asOf);
		}
		catch (RuntimeException ex) {
			trial = null;
			ApiErrors.show(ex);
		}
	}

	public LocalDate getAsOf() {
		return asOf;
	}

	public void setAsOf(LocalDate asOf) {
		this.asOf = asOf;
	}

	/** From the response, not the inputs: the input box may have changed since the report ran. */
	public String getTrialHeading() {
		return trial == null ? "" : "As of " + trial.asOf();
	}

	/** From the response, not the inputs: the input boxes may have changed since the report ran. */
	public String getLedgerHeading() {
		return ledger == null ? "" : ledger.accountCode() + " " + ledger.accountName() + ", " + ledger.from() + " to " + ledger.to();
	}

	public List<TrialBalanceDto.Row> getTrialRows() {
		return trial == null ? List.of() : trial.rows();
	}

	public String getTrialTotals() {
		return trial == null ? ""
				: "Debit " + Idr.format(trial.totalDebit()) + " · Credit " + Idr.format(trial.totalCredit()) + " · Difference "
						+ Idr.format(trial.difference());
	}

	public ListModel<Object> getAccountModel() {
		return accountModel;
	}

	public String getLedgerAccountText() {
		return ledgerAccountText;
	}

	public void setLedgerAccountText(String ledgerAccountText) {
		this.ledgerAccountText = ledgerAccountText;
	}

	public LocalDate getLedgerFrom() {
		return ledgerFrom;
	}

	public void setLedgerFrom(LocalDate ledgerFrom) {
		this.ledgerFrom = ledgerFrom;
	}

	public LocalDate getLedgerTo() {
		return ledgerTo;
	}

	public void setLedgerTo(LocalDate ledgerTo) {
		this.ledgerTo = ledgerTo;
	}

	public GeneralLedgerDto getLedger() {
		return ledger;
	}

	public List<GeneralLedgerDto.Line> getLedgerLines() {
		return ledger == null ? List.of() : ledger.lines();
	}

	public String getLedgerOpening() {
		return ledger == null ? "" : "Opening balance " + Idr.format(ledger.openingBalance());
	}

	public String getLedgerClosing() {
		return ledger == null ? "" : "Closing balance " + Idr.format(ledger.closingBalance());
	}

}
