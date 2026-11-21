package com.dancingcat.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zamanlı şarkı sözleri. LRC formatından okunur:
 * <pre>[00:12.34] Bir satır söz</pre>
 */
public record Lyrics(List<Line> lines, boolean instrumental) {

    public record Line(long timeMs, String text) {}

    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d{1,2}(?:[.:]\\d{1,3})?)]");

    public static Lyrics instrumentalTrack() {
        return new Lyrics(List.of(), true);
    }

    public static Lyrics parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher m = TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            // Bir satırda birden fazla zaman etiketi olabilir: [00:10.00][00:45.00] Nakarat
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                double sec = Double.parseDouble(m.group(2).replace(':', '.'));
                times.add(min * 60_000 + Math.round(sec * 1000));
                end = m.end();
            }
            String text = raw.substring(end).trim();
            for (long t : times) lines.add(new Line(t, text));
        }
        lines.sort(Comparator.comparingLong(Line::timeMs));
        return new Lyrics(List.copyOf(lines), false);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** Verilen andaki satırın indeksi; ilk satırdan önceyse -1. */
    public int indexAt(long ms) {
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs() <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }
}
