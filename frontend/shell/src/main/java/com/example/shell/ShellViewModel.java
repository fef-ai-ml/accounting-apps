package com.example.shell;

import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.ModuleRegistry;
import com.example.ui.common.SpringBeanVariableResolver;
import com.example.ui.common.UiModule;

/** Navigation between the UI modules registered in this deployment that the signed-in user may open. */
@VariableResolver(SpringBeanVariableResolver.class)
public class ShellViewModel {

	@WireVariable
	private ModuleRegistry moduleRegistry;

	@WireVariable
	private CurrentUser currentUser;

	private List<UiModule> modules = List.of();

	private String userName = "";

	private UiModule current;

	@Init
	public void init() {
		try {
			modules = moduleRegistry.modules().stream()
					.filter(module -> currentUser.can(module.requiredPermission()))
					.toList();
			userName = currentUser.getName();
		}
		catch (RuntimeException ex) {
			// Backend unreachable or login gone: render an empty shell and tell the user.
			ApiErrors.show(ex);
		}
		current = modules.isEmpty() ? null : modules.get(0);
	}

	@Command
	@NotifyChange({ "current", "entry", "remoteUrl" })
	public void open(@BindingParam("module") UiModule module) {
		current = module;
	}

	public List<UiModule> getModules() {
		return modules;
	}

	public String getUserName() {
		return userName;
	}

	public UiModule getCurrent() {
		return current;
	}

	public String getEntry() {
		return current == null || current.isRemote() ? null : current.entry();
	}

	public String getRemoteUrl() {
		return current == null ? null : current.remoteUrl();
	}

}
