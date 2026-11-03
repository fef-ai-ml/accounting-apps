package com.example.shell;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Page;
import org.zkoss.zul.Button;
import org.zkoss.zul.Combobox;

/** Drives real ZK pages the way a browser does: every click and change is an AU request to the running test server. */
final class ZkDriver {

	private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

	private final String base;

	private volatile Desktop desktop;

	private int sid = 1;

	String response = "";

	ZkDriver(int port) {
		base = "http://localhost:" + port;
	}

	/** Call from a mocked bean: during a render it is the one place a test can reach the server's desktop from. */
	void captureDesktop() {
		if (Executions.getCurrent() != null) {
			desktop = Executions.getCurrent().getDesktop();
		}
	}

	void open(String path) throws Exception {
		response = send(HttpRequest.newBuilder(URI.create(base + path)));
	}

	/** Sends commands in one request, as ZK's client queue does; arguments come in threes: command, uuid, data. */
	void au(String... commands) throws Exception {
		StringBuilder body = new StringBuilder("dtid=" + desktop.getId());
		for (int i = 0; i < commands.length / 3; i++) {
			body.append("&cmd_" + i + "=" + commands[i * 3] + "&uuid_" + i + "=" + commands[i * 3 + 1] + "&data_" + i + "="
					+ URLEncoder.encode(commands[i * 3 + 2], StandardCharsets.UTF_8));
		}
		response = send(HttpRequest.newBuilder(URI.create(base + "/zkau"))
				.header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8").header("ZK-SID", String.valueOf(sid++))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())));
	}

	void click(Component component) throws Exception {
		au("onClick", component.getUuid(), "{\"pageX\":1,\"pageY\":1,\"which\":1,\"x\":1,\"y\":1}");
	}

	void change(Component component, String text) throws Exception {
		au("onChange", component.getUuid(), "{\"value\":\"" + text + "\",\"start\":" + text.length() + "}");
	}

	/** Filters the list by typing, then selects the item, in the requests a browser's combobox sends. */
	void pick(Combobox box, String label) throws Exception {
		String typed = "{\"value\":\"" + label + "\",\"start\":" + label.length() + "}";
		au("onChanging", box.getUuid(), typed);
		String item = box.getItems().stream().filter(option -> option.getLabel().equals(label)).findFirst().orElseThrow().getUuid();
		au("onChange", box.getUuid(), typed, "onSelect", box.getUuid(), "{\"items\":[\"" + item + "\"],\"reference\":\"" + item + "\"}");
	}

	/** Fetches the file the last response asked the browser to download: its URL, which ends in its name, and its text. */
	List<String> download() throws Exception {
		Matcher url = Pattern.compile("/zkau/view/[^\"']+").matcher(response.replace("\\/", "/").replace("\\-", "-"));
		if (!url.find()) {
			throw new AssertionError("no download in " + response);
		}
		return List.of(url.group(), send(HttpRequest.newBuilder(URI.create(base + url.group()))));
	}

	/** Components of this type on the desktop, in page order: a dialog comes after the window it covers. */
	<T extends Component> List<T> all(Class<T> type) {
		List<T> found = new ArrayList<>();
		for (Page page : desktop.getPages()) {
			page.getRoots().forEach(root -> collect(root, type, found));
		}
		return found;
	}

	Button button(String label) {
		return all(Button.class).stream().filter(button -> label.equals(button.getLabel())).findFirst().orElseThrow();
	}

	private String send(HttpRequest.Builder request) throws Exception {
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
	}

	private static <T extends Component> void collect(Component component, Class<T> type, List<T> found) {
		if (type.isInstance(component)) {
			found.add(type.cast(component));
		}
		component.getChildren().forEach(child -> collect(child, type, found));
	}

}
