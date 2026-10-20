package io.github.samuelfonseca.tvhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The data files shipped in the repo: wallpapers, the default dock, and the debloat lists. */
class RepoFilesTest {
    private static final Path ROOT = Path.of(System.getProperty("repo", ".."));
    private static final Path LAUNCHER = ROOT.resolve("launcher");
    private static final Pattern PACKAGE = Pattern.compile("[a-zA-Z]\\w*(\\.[a-zA-Z_]\\w*)+");

    private static BufferedReader open(Path p) throws IOException {
        return Files.newBufferedReader(p, StandardCharsets.UTF_8);
    }

    @Test void everyWallpaperIsListedBundledAndCredited() throws IOException {
        Set<String> listed = new TreeSet<>();
        try (BufferedReader r = open(LAUNCHER.resolve("res/raw/walls.txt"))) {
            for (String[] w : Lists.wallpapers(r)) listed.add(w[0]);
        }
        Set<String> bundled;
        try (Stream<Path> files = Files.list(LAUNCHER.resolve("res/drawable-nodpi"))) {
            bundled = files.map(p -> p.getFileName().toString().replaceFirst("\\.jpg$", ""))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(bundled, listed, "walls.txt and res/drawable-nodpi disagree");

        String credits = Files.readString(LAUNCHER.resolve("WALLPAPERS.md"));
        for (String w : listed) assertTrue(credits.contains("| " + w + " |"), w + " has no row in WALLPAPERS.md");
    }

    @Test void wallpaperListHasNoMalformedLines() throws IOException {
        List<String> lines = Files.readAllLines(LAUNCHER.resolve("res/raw/walls.txt"));
        lines.removeIf(String::isBlank);
        try (BufferedReader r = open(LAUNCHER.resolve("res/raw/walls.txt"))) {
            assertEquals(lines.size(), Lists.wallpapers(r).size(), "a walls.txt line doesn't have 5 fields");
        }
    }

    @Test void defaultDockHasValidEntries() throws IOException {
        List<String> dock;
        try (BufferedReader r = open(LAUNCHER.resolve("res/raw/dock.txt"))) {
            dock = Lists.entries(r);
        }
        assertFalse(dock.isEmpty());
        assertEquals(dock.size(), new HashSet<>(dock).size(), "duplicate dock entry");
        for (String e : dock) {
            assertTrue(e.startsWith("input:") || PACKAGE.matcher(e).matches(), "bad dock entry: " + e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"config/debloat.txt", "examples/debloat-tcl-g07.txt"})
    void debloatListsAreValidPackageNames(String list) throws IOException {
        List<String> pkgs;
        try (BufferedReader r = open(ROOT.resolve(list))) {
            pkgs = Lists.entries(r);
        }
        assertEquals(pkgs.size(), new HashSet<>(pkgs).size(), "duplicate package in " + list);
        for (String p : pkgs) assertTrue(PACKAGE.matcher(p).matches(), "bad package name in " + list + ": " + p);
    }

    /**
     * Disabling launcherx while setupwraith is enabled makes setupwraith delete the saved Wi-Fi.
     * tools/set-home.sh disables them in a safe order, so the lists must never do it themselves.
     */
    @ParameterizedTest
    @ValueSource(strings = {"config/debloat.txt", "examples/debloat-tcl-g07.txt"})
    void debloatListsLeaveHomeAndSetupToSetHome(String list) throws IOException {
        List<String> pkgs;
        try (BufferedReader r = open(ROOT.resolve(list))) {
            pkgs = Lists.entries(r);
        }
        assertFalse(pkgs.contains("com.google.android.apps.tv.launcherx"), list + " disables launcherx");
        assertFalse(pkgs.contains("com.google.android.tungsten.setupwraith"), list + " disables setupwraith");
    }
}
