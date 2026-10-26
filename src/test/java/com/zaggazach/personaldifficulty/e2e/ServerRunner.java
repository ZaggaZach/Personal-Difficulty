package com.zaggazach.personaldifficulty.e2e;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs a REAL Fabric dedicated server (installed with the official Fabric installer, with the built Personal Difficulty
 * jar, the e2e fixture mod and Fabric API in mods/) as a separate OS process. Used for end-to-end tests, including kill -9.
 */
public final class ServerRunner implements AutoCloseable {
	// Versions come from gradle.properties via the e2eTest task, so the harness cannot drift from the project's toolchain.
	public static final String MC_VERSION = System.getProperty("pd.e2e.minecraft", "26.3");
	public static final String LOADER_VERSION = System.getProperty("pd.e2e.loader", "0.20.1");
	public static final String INSTALLER_VERSION = "1.1.2";
	public static final String FABRIC_API_VERSION = System.getProperty("pd.e2e.fabricApi", "0.165.0+26.3");

	private final Path dir;
	private final int port;
	private Process process;
	private BufferedWriter stdin;
	public final List<String> log = new CopyOnWriteArrayList<>();

	public ServerRunner(Path dir) throws IOException {
		this.dir = dir;
		try (ServerSocket s = new ServerSocket(0)) {
			this.port = s.getLocalPort();
		}
	}

	public int port() {
		return port;
	}

	public Path dir() {
		return dir;
	}

	public Path world() {
		return dir.resolve("world");
	}

	private static Path cacheDir() {
		return Path.of(System.getProperty("pd.e2e.cache", "build/e2e/cache")).toAbsolutePath();
	}

	private static void download(String url, Path target) throws IOException, InterruptedException {
		if (Files.exists(target) && Files.size(target) > 0) {
			return;
		}
		Files.createDirectories(target.getParent());
		HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
		HttpResponse<Path> r = client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(target.resolveSibling(target.getFileName() + ".part")));
		if (r.statusCode() != 200) {
			throw new IOException("HTTP " + r.statusCode() + " for " + url);
		}
		Files.move(r.body(), target, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Installs Fabric server + Minecraft server jar once (cached), returns the template directory. */
	private static synchronized Path baseInstall() throws Exception {
		Path base = cacheDir().resolve("base-install-" + MC_VERSION + "-" + LOADER_VERSION);
		if (Files.exists(base.resolve("fabric-server-launch.jar")) && Files.exists(base.resolve("server.jar"))) {
			return base;
		}
		Path installer = cacheDir().resolve("fabric-installer-" + INSTALLER_VERSION + ".jar");
		download("https://maven.fabricmc.net/net/fabricmc/fabric-installer/" + INSTALLER_VERSION + "/fabric-installer-" + INSTALLER_VERSION + ".jar", installer);
		Files.createDirectories(base);
		Process p = new ProcessBuilder(javaBin(), "-jar", installer.toString(), "server", "-dir", base.toString(), "-mcversion", MC_VERSION,
			"-loader", LOADER_VERSION, "-downloadMinecraft").redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!p.waitFor(300, TimeUnit.SECONDS) || p.exitValue() != 0) {
			throw new IOException("Fabric installer failed:\n" + out);
		}
		return base;
	}

	private static String javaBin() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	private static void copyTree(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path src : walk.toList()) {
				Path dst = to.resolve(from.relativize(src).toString());
				if (Files.isDirectory(src)) {
					Files.createDirectories(dst);
				} else {
					Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/** Creates the server directory (fresh) with the mods installed. Keeps an existing world if {@code keepWorld}. */
	public void prepare(boolean keepWorld) throws Exception {
		Path base = baseInstall();
		Path world = world();
		Path savedWorld = null;
		if (keepWorld && Files.exists(world)) {
			savedWorld = dir.resolveSibling(dir.getFileName() + "-world-keep");
			deleteTree(savedWorld);
			Files.move(world, savedWorld);
		}
		deleteTree(dir);
		Files.createDirectories(dir);
		copyTree(base, dir);
		if (savedWorld != null) {
			Files.move(savedWorld, world);
		}
		installMods();
		writeProperties();
	}

	public void installMods() throws Exception {
		Path mods = dir.resolve("mods");
		deleteTree(mods);
		Files.createDirectories(mods);
		Path api = cacheDir().resolve("fabric-api-" + FABRIC_API_VERSION + ".jar");
		download("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/" + FABRIC_API_VERSION + "/fabric-api-" + FABRIC_API_VERSION + ".jar", api);
		Files.copy(api, mods.resolve(api.getFileName()));
		// the JAR under test (built by the normal `jar` task) and the small test-only fixture mod that opens a test menu
		for (String property : List.of("pd.jar", "pd.fixture.jar")) {
			String configured = System.getProperty(property);
			if (configured == null) {
				throw new IllegalStateException("system property " + property + " is not set; run through `./gradlew e2eTest`");
			}
			Path jar = Path.of(configured).toAbsolutePath();
			Files.copy(jar, mods.resolve(jar.getFileName()));
		}
		Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
	}

	private void writeProperties() throws IOException {
		Files.writeString(dir.resolve("server.properties"), String.join("\n",
			"online-mode=false",
			"server-port=" + port,
			"motd=PersonalDifficulty e2e",
			"level-name=world",
			"spawn-protection=0",
			"view-distance=2",
			"simulation-distance=2",
			"max-players=20",
			"gamemode=survival",
			"difficulty=peaceful",
			"spawn-monsters=false",
			"spawn-animals=false",
			"spawn-npcs=false",
			"generate-structures=false",
			"level-type=minecraft\\:flat",
			"sync-chunk-writes=true",
			"player-idle-timeout=0",
			"pause-when-empty-seconds=-1",
			"enable-rcon=false",
			"white-list=false",
			"enforce-whitelist=false",
			"") + "\n");
	}

	public void start() throws Exception {
		log.clear();
		List<String> cmd = new ArrayList<>(List.of(javaBin(), "-Xmx1G", "-Dfabric.addMods="));
		if (Boolean.getBoolean("pd.e2e.serverDebug")) {
			Path cfg = dir.resolve("log4j-debug.xml");
			try {
				Files.writeString(cfg, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Configuration status=\"warn\"><Appenders><Console name=\"C\" target=\"SYSTEM_OUT\">"
					+ "<PatternLayout pattern=\"[%d{HH:mm:ss.SSS}] [%t/%level] (%c{1}) %msg{nolookups}%n\"/></Console></Appenders><Loggers><Root level=\"debug\"><AppenderRef ref=\"C\"/></Root></Loggers></Configuration>");
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			cmd.add("-Dlog4j.configurationFile=" + cfg);
		}
		cmd.addAll(List.of("-jar", "fabric-server-launch.jar", "nogui"));
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(dir.toFile());
		pb.redirectErrorStream(true);
		process = pb.start();
		stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		Thread reader = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					log.add(line);
				}
			} catch (IOException ignored) {
			}
		}, "server-log");
		reader.setDaemon(true);
		reader.start();
		if (!awaitLog("Done (", 180_000)) {
			throw new IllegalStateException("server did not start; log tail:\n" + tail(60));
		}
		// Fabric API refuses connections that arrive before the SERVER_STARTED event (slightly after vanilla's "Done"). The mod
		// logs its "ready" line earlier (SERVER_STARTING), so instead prove the server loop is running by asking it a question.
		if (!awaitLog("Personal Difficulty ready", 5_000)) {
			throw new IllegalStateException("the mod did not report ready; log tail:\n" + tail(60));
		}
		int mark = log.size();
		console("list");
		if (!awaitLogAfter("players online", mark, 30_000)) {
			throw new IllegalStateException("the server did not answer a console command; log tail:\n" + tail(60));
		}
	}

	public boolean awaitLog(String fragment, long timeoutMillis) throws InterruptedException {
		return awaitLogAfter(fragment, 0, timeoutMillis);
	}

	public int logSize() {
		return log.size();
	}

	public boolean awaitLogAfter(String fragment, int fromIndex, long timeoutMillis) throws InterruptedException {
		long end = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < end) {
			List<String> snapshot = new ArrayList<>(log);
			for (int i = Math.min(fromIndex, snapshot.size()); i < snapshot.size(); i++) {
				if (snapshot.get(i).contains(fragment)) {
					return true;
				}
			}
			if (process != null && !process.isAlive()) {
				return false;
			}
			Thread.sleep(50);
		}
		return false;
	}

	public String tail(int lines) {
		List<String> l = new ArrayList<>(log);
		return String.join("\n", l.subList(Math.max(0, l.size() - lines), l.size()));
	}

	public synchronized void console(String command) throws IOException {
		stdin.write(command);
		stdin.newLine();
		stdin.flush();
	}

	public long pid() {
		return process.pid();
	}

	public boolean isAlive() {
		return process != null && process.isAlive();
	}

	/** Graceful stop (saves everything). */
	public void stop() throws Exception {
		if (process == null || !process.isAlive()) {
			return;
		}
		console("stop");
		if (!process.waitFor(120, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("server did not stop in time; log tail:\n" + tail(40));
		}
	}

	/** SIGKILL: no shutdown hooks, no final save - what a power cut or OOM-kill looks like to the process. */
	public void kill() throws Exception {
		if (process != null && process.isAlive()) {
			process.destroyForcibly();
			process.waitFor(30, TimeUnit.SECONDS);
		}
	}

	public static void deleteTree(Path p) throws IOException {
		if (!Files.exists(p)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(p)) {
			for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(x);
			}
		}
	}

	@Override
	public void close() throws Exception {
		if (process != null && process.isAlive()) {
			process.destroyForcibly();
			process.waitFor(30, TimeUnit.SECONDS);
		}
	}
}
