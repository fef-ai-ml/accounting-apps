package com.example.ui.accounting;

import java.time.LocalDate;
import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind audit.zul: the read-only, server-side paged audit log. */
@VariableResolver(SpringBeanVariableResolver.class)
public class AuditViewModel {

	static final int PAGE_SIZE = 50;

	@WireVariable
	private AccountingApiClient accountingApiClient;

	private LocalDate from;

	private LocalDate to;

	private String action = "";

	private int page;

	private long total;

	private List<AuditRowDto> rows = List.of();

	@Init
	public void init() {
		load();
	}

	@Command
	@NotifyChange({ "rows", "total", "page" })
	public void search() {
		page = 0;
		load();
	}

	@Command
	@NotifyChange({ "rows", "total", "page" })
	public void goToPage(@BindingParam("page") int page) {
		this.page = page;
		load();
	}

	private void load() {
		try {
			AuditPageDto result = accountingApiClient.audit(from, to, action, page, PAGE_SIZE);
			rows = result.items();
			total = result.total();
		}
		catch (RuntimeException ex) {
			page = 0;
			rows = List.of();
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

	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public List<String> getActions() {
		return List.of("", "SETUP", "POST", "REVERSE", "PERIOD_CLOSE", "PERIOD_REOPEN", "SETTING_CHANGE", "ACCOUNT_CHANGE");
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

	public List<AuditRowDto> getRows() {
		return rows;
	}

}
