package com.example.ui.accounting;

import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind accounts.zul: setup, the settings panel and the chart of accounts. */
@VariableResolver(SpringBeanVariableResolver.class)
public class AccountsViewModel {

	private static final List<String> TYPES = List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE");

	@WireVariable
	private AccountingApiClient accountingApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private boolean initialized;

	private boolean loadFailed;

	private boolean requireSeparatePoster = true;

	private boolean canManage;

	private List<AccountRow> rows = List.of();

	private boolean editing;

	private Long editingId;

	private Long parentId;

	private String parentLabel = "";

	private String code = "";

	private String name = "";

	private String type = "ASSET";

	private boolean postable = true;

	@Init
	public void init() {
		try {
			canManage = currentUser.can("accounting:account-manage");
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
	}

	@Command
	@NotifyChange("*")
	public void setup() {
		try {
			accountingApiClient.setup();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
	}

	@Command
	@NotifyChange("*")
	public void setSeparatePoster(@BindingParam("checked") boolean checked) {
		try {
			requireSeparatePoster = accountingApiClient.updateSettings(checked).requireSeparatePoster();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
			load();
		}
	}

	@Command
	@NotifyChange("*")
	public void addChild(@BindingParam("account") AccountDto parent) {
		editing = true;
		editingId = null;
		parentId = parent == null ? null : parent.id();
		parentLabel = parent == null ? "None (top level)" : parent.getLabel();
		code = "";
		name = "";
		type = parent == null ? "ASSET" : parent.type();
		postable = true;
	}

	@Command
	@NotifyChange("*")
	public void edit(@BindingParam("account") AccountDto account) {
		editing = true;
		editingId = account.id();
		parentId = account.parentId();
		parentLabel = rows.stream().map(AccountRow::getAccount).filter(other -> other.id().equals(account.parentId()))
				.map(AccountDto::getLabel).findFirst().orElse("None (top level)");
		code = account.code();
		name = account.name();
		type = account.type();
		postable = account.postable();
	}

	@Command
	@NotifyChange("*")
	public void cancel() {
		editing = false;
	}

	@Command
	@NotifyChange("*")
	public void save() {
		if (code == null || code.isBlank() || name == null || name.isBlank()) {
			Clients.showNotification("Enter a code and a name", "warning", null, "middle_center", 3000);
			return;
		}
		// No active: a new account starts active, and an update keeps the value, which the form may have outlived.
		AccountRequest request = new AccountRequest(code.strip(), name.strip(), type, parentId, postable, null);
		try {
			if (editingId == null) {
				accountingApiClient.createAccount(request);
			}
			else {
				accountingApiClient.updateAccount(editingId, request);
			}
			editing = false;
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
			return;
		}
		load();
	}

	@Command
	@NotifyChange("*")
	public void toggleActive(@BindingParam("account") AccountDto account) {
		try {
			accountingApiClient.updateAccount(account.id(), new AccountRequest(account.code(), account.name(), account.type(),
					account.parentId(), account.postable(), !account.active()));
			editing = false;
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
	}

	private void load() {
		loadFailed = false;
		try {
			SettingsDto settings = accountingApiClient.settings();
			initialized = settings.initialized();
			requireSeparatePoster = settings.requireSeparatePoster();
			rows = AccountTree.rows(accountingApiClient.accounts());
		}
		catch (RuntimeException ex) {
			loadFailed = true;
			rows = List.of();
			ApiErrors.show(ex);
		}
	}

	public boolean isInitialized() {
		return initialized;
	}

	public boolean isLoadFailed() {
		return loadFailed;
	}

	public boolean isRequireSeparatePoster() {
		return requireSeparatePoster;
	}

	public boolean isCanManage() {
		return canManage;
	}

	public List<AccountRow> getRows() {
		return rows;
	}

	public boolean isEditing() {
		return editing;
	}

	public Long getEditingId() {
		return editingId;
	}

	public String getParentLabel() {
		return parentLabel;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public List<String> getTypes() {
		return TYPES;
	}

	public boolean isPostable() {
		return postable;
	}

	public void setPostable(boolean postable) {
		this.postable = postable;
	}

}
