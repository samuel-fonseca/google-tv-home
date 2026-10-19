package io.github.samuelfonseca.tvhome;

/** Remote navigation in the app grid. Plain Java, so the tests run without Android. */
final class Grid {
    private Grid() {}

    /**
     * Where Down goes from tile {@code index} of {@code count}, {@code columns} wide: the tile
     * below, or the last tile when the next row is too short to have one; -1 from the last row.
     */
    static int below(int index, int count, int columns) {
        int nextRow = (index / columns + 1) * columns;
        if (nextRow >= count) return -1;
        return Math.min(index + columns, count - 1);
    }
}
