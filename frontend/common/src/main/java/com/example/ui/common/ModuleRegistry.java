package com.example.ui.common;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/** All {@link UiModule}s present in this deployment, in display order. */
@Component
public class ModuleRegistry {

	private final List<UiModule> modules;

	public ModuleRegistry(List<UiModule> modules) {
		this.modules = modules.stream().sorted(Comparator.comparingInt(UiModule::order)).toList();
	}

	public List<UiModule> modules() {
		return modules;
	}

}
