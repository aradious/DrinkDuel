package com.drinkduel.game;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable raw and Wild-1 aggregate counts for Reveal. */
public record LiarsDiceRevealCounts(Map<Integer, Integer> raw, Map<Integer, Integer> effective) {
    public LiarsDiceRevealCounts {
        raw = immutableFaces(raw);
        effective = immutableFaces(effective);
    }

    static LiarsDiceRevealCounts from(Iterable<DiceHand> hands) {
        var raw = emptyFaces();
        for (DiceHand hand : hands) {
            hand.values().forEach(face -> raw.compute(face, (ignored, count) -> count + 1));
        }
        var effective = emptyFaces();
        effective.put(1, raw.get(1));
        for (int face = 2; face <= 6; face++) {
            effective.put(face, raw.get(face) + raw.get(1));
        }
        return new LiarsDiceRevealCounts(raw, effective);
    }

    public int rawCount(int face) {
        return requireFace(raw, face);
    }

    public int effectiveCount(int face) {
        return requireFace(effective, face);
    }

    private static LinkedHashMap<Integer, Integer> emptyFaces() {
        var counts = new LinkedHashMap<Integer, Integer>();
        for (int face = 1; face <= 6; face++) counts.put(face, 0);
        return counts;
    }

    private static Map<Integer, Integer> immutableFaces(Map<Integer, Integer> source) {
        if (source == null || source.size() != 6) throw new IllegalArgumentException("Counts require faces 1 through 6");
        var copy = new LinkedHashMap<Integer, Integer>();
        for (int face = 1; face <= 6; face++) {
            Integer count = source.get(face);
            if (count == null || count < 0) throw new IllegalArgumentException("Invalid count for face " + face);
            copy.put(face, count);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static int requireFace(Map<Integer, Integer> counts, int face) {
        Integer count = counts.get(face);
        if (count == null) throw new IllegalArgumentException("Face must be between 1 and 6");
        return count;
    }
}
