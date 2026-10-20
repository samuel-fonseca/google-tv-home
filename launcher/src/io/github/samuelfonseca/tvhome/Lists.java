package io.github.samuelfonseca.tvhome;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The bundled text lists in res/raw. Plain Java, so the tests run without Android. */
final class Lists {
    private Lists() {}

    /** dock.txt: one entry per line; # starts a comment, blank lines are skipped. */
    static List<String> entries(BufferedReader r) throws IOException {
        List<String> entries = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            line = line.replaceAll("#.*", "").trim();
            if (!line.isEmpty()) entries.add(line);
        }
        return entries;
    }

    /**
     * walls.txt: {resName, place, author, license, category} per line, split on |. Other lines are
     * skipped.
     */
    static List<String[]> wallpapers(BufferedReader r) throws IOException {
        List<String[]> walls = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            String[] parts = line.split("\\|");
            if (parts.length == 5) walls.add(parts);
        }
        return walls;
    }

    /** The photos' categories, in the order they first appear. */
    static List<String> categories(List<String[]> walls) {
        List<String> categories = new ArrayList<>();
        for (String[] w : walls) if (!categories.contains(w[4])) categories.add(w[4]);
        return categories;
    }

    /**
     * The photos in any of the {@code chosen} categories. Every photo when none are chosen, or none
     * of the chosen still exist, so there is always something to show.
     */
    static List<String[]> inCategories(List<String[]> walls, Collection<String> chosen) {
        List<String[]> picked = new ArrayList<>();
        for (String[] w : walls) if (chosen.contains(w[4])) picked.add(w);
        return picked.isEmpty() ? walls : picked;
    }

    /** Whether {@code category} shows: saved as chosen, or every one does when none of the chosen exist. */
    static boolean ticked(List<String> all, Set<String> chosen, String category) {
        for (String c : all) if (chosen.contains(c)) return chosen.contains(category);
        return true;
    }

    /**
     * The categories to save after ticking or unticking {@code category}. All of them is saved as
     * none, so categories added in an update show up too; the last one ticked stays ticked, so
     * there is always something to show.
     */
    static Set<String> toggle(List<String> all, Set<String> chosen, String category) {
        Set<String> ticked = new LinkedHashSet<>();
        for (String c : all) if (ticked(all, chosen, c)) ticked.add(c);
        if (!ticked.remove(category)) ticked.add(category);
        else if (ticked.isEmpty()) ticked.add(category);
        return ticked.size() == all.size() ? new HashSet<>() : ticked;
    }
}
