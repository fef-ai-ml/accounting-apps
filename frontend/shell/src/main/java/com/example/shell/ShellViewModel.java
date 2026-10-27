package com.example.shell;

import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;

import com.example.ui.common.ModuleRegistry;
import com.example.ui.common.SpringBeanVariableResolver;
import com.example.ui.common.UiModule;

/** Navigation between the UI modules registered in this deployment. */
@VariableResolver(SpringBeanVariableResolver.class)
public class ShellViewModel {

	@WireVariable
	private ModuleRegistry moduleRegistry;

	private UiModule current;

	@Init
	public void init() {
		List<UiModule> modules = moduleRegistry.modules();
		current = modules.isEmpty() ? null : modules.get(0);
	}

	@Command
	@NotifyChange({ "current", "entry", "remoteUrl" })
	public void open(@BindingParam("module") UiModule module) {
		current = module;
	}

	public List<UiModule> getModules() {
		return moduleRegistry.modules();
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
