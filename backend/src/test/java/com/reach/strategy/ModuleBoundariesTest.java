package com.reach.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Enforces the modular-monolith rules of AGENTS.md §2 on the main source tree, without extra
 * tooling: a module may use another feature module only through its {@code api} package, and
 * {@code platform} (technical foundation) depends on no feature module. Checked via imports and
 * fully qualified references in the sources.
 */
class ModuleBoundariesTest {

	private static final Path MAIN_SOURCES = Path.of("src/main/java/com/reach/strategy");
	private static final String ROOT = "com.reach.strategy.";
	private static final Pattern REFERENCE = Pattern.compile("\\bcom\\.reach\\.strategy\\.(\\w+)\\.(\\w+)");

	@Test
	void modulesOnlyUseEachOthersPublicApi() throws IOException {
		List<String> violations = new ArrayList<>();
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				String module = moduleOf(file);
				if (module == null) {
					continue;
				}
				Matcher matcher = REFERENCE.matcher(Files.readString(file));
				while (matcher.find()) {
					String target = matcher.group(1);
					String layer = matcher.group(2);
					if (target.equals(module) || isAllowed(module, target, layer)) {
						continue;
					}
					violations.add(MAIN_SOURCES.relativize(file) + " -> " + ROOT + target + "." + layer);
				}
			}
		}
		assertThat(violations).as("cross-module references outside public api packages").isEmpty();
	}

	/**
	 * No two modules depend on each other, directly or through others: consumers define the SPIs
	 * they need in their own api package and providers implement them (e.g. diplomacy implements
	 * military.api.Hostility; military never references diplomacy).
	 */
	@Test
	void moduleDependenciesHaveNoCycles() throws IOException {
		Map<String, Set<String>> uses = new TreeMap<>();
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				String module = moduleOf(file);
				if (module == null) {
					continue;
				}
				Set<String> targets = uses.computeIfAbsent(module, m -> new TreeSet<>());
				Matcher matcher = REFERENCE.matcher(Files.readString(file));
				while (matcher.find()) {
					if (!matcher.group(1).equals(module)) {
						targets.add(matcher.group(1));
					}
				}
			}
		}
		List<String> cycles = new ArrayList<>();
		for (String start : uses.keySet()) {
			if (reaches(uses, start, start, new HashSet<>())) {
				cycles.add(start);
			}
		}
		assertThat(cycles).as("modules on a dependency cycle, graph " + uses).isEmpty();
		uses.forEach((module, targets) -> assertThat(targets).as(module).doesNotContain("diplomacy"));
	}

	private static boolean reaches(Map<String, Set<String>> uses, String from, String goal, Set<String> seen) {
		for (String next : uses.getOrDefault(from, Set.of())) {
			if (next.equals(goal)) {
				return true;
			}
			if (seen.add(next) && reaches(uses, next, goal, seen)) {
				return true;
			}
		}
		return false;
	}

	/** AGENTS.md §2: rules packages are pure Java — no Spring, JPA, Jackson or database. */
	@Test
	void rulesPackagesArePureJava() throws IOException {
		List<String> violations = new ArrayList<>();
		Pattern forbidden = Pattern.compile("^import\\s+(org\\.springframework|jakarta\\.persistence|tools\\.jackson|"
				+ "com\\.fasterxml|java\\.sql|javax\\.sql|java\\.time\\.Clock|java\\.util\\.Random)", Pattern.MULTILINE);
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> path.getParent().getFileName().toString().equals("rules"))
				.toList()) {
				Matcher matcher = forbidden.matcher(Files.readString(file));
				if (matcher.find()) {
					violations.add(MAIN_SOURCES.relativize(file) + " imports " + matcher.group(1));
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	@Test
	void theBoundaryCheckSeesTheModules() throws IOException {
		try (Stream<Path> modules = Files.list(MAIN_SOURCES)) {
			assertThat(modules.filter(Files::isDirectory).map(path -> path.getFileName().toString()))
				.contains("platform", "identity", "game", "world");
		}
	}

	private static boolean isAllowed(String fromModule, String toModule, String layer) {
		if (fromModule.equals("platform")) {
			return false;
		}
		// Feature modules may use the technical foundation and other modules' public api.
		return toModule.equals("platform") || layer.equals("api");
	}

	private static String moduleOf(Path file) {
		Path relative = MAIN_SOURCES.relativize(file);
		return relative.getNameCount() > 1 ? relative.getName(0).toString() : null;
	}

}
