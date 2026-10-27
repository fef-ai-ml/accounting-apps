package com.example.ui.common;

/**
 * What a UI module tells the shell about itself. Each UI module contributes one bean of this type.
 *
 * <ul>
 * <li>{@code entry}: ZUL page rendered inside the shell, e.g. {@code ~./task/main.zul} (module is in the same app).</li>
 * <li>{@code remoteUrl}: URL of a module deployed as its own app, shown in an iframe (module was split out).</li>
 * </ul>
 */
public record UiModule(String id, String title, int order, String entry, String remoteUrl) {

	public static UiModule embedded(String id, String title, int order, String entry) {
		return new UiModule(id, title, order, entry, null);
	}

	public static UiModule remote(String id, String title, int order, String remoteUrl) {
		return new UiModule(id, title, order, null, remoteUrl);
	}

	// JavaBean getters: ZK's EL cannot read record accessors like title().

	public String getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public boolean isRemote() {
		return remoteUrl != null;
	}

}
