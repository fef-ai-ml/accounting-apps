package com.example.ui.common;

/**
 * What a UI module tells the shell about itself. Each UI module contributes one bean of this type.
 *
 * <ul>
 * <li>{@code entry}: ZUL page rendered inside the shell, e.g. {@code ~./task/main.zul} (module is in the same app).</li>
 * <li>{@code remoteUrl}: URL of a module deployed as its own app, shown in an iframe (module was split out).</li>
 * <li>{@code requiredPermission}: permission the user needs to see the module in the navigation, or {@code null}.
 * Hiding is for clarity only; the backend enforces permissions on every call.</li>
 * </ul>
 */
public record UiModule(String id, String title, int order, String entry, String remoteUrl, String requiredPermission) {

	public static UiModule embedded(String id, String title, int order, String entry) {
		return new UiModule(id, title, order, entry, null, null);
	}

	public static UiModule remote(String id, String title, int order, String remoteUrl) {
		return new UiModule(id, title, order, null, remoteUrl, null);
	}

	public UiModule withPermission(String permission) {
		return new UiModule(id, title, order, entry, remoteUrl, permission);
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
