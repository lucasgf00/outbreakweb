/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic moderation of the website chat (and of the bug reports, names and room titles shown on the page).
 *
 *  Characters (sanitize): control and format characters out (zero-width, bidi overrides/isolates, BOM...),
 *    at most 2 combining marks per letter (zalgo), runs of one character cut to 6, at most 10 emoji/symbols
 *    per message, whitespace folded. Sustained capitals (12+ letters, >70 % upper case) become lower case.
 *  Spam (check): the same text (folded) as the previous message of the same name or address in the last
 *    2 minutes -> "repeated"; more than 1 link in one message -> "too many links"; more than 2 messages with
 *    links per name/address in 5 minutes -> "link flood".
 *  Owner's word list: api.badwords_file (default data/badwords.txt), one entry per line, '#' comments,
 *    empty by default. "word" = whole word, "word*" = words starting with it, "*word*" = anywhere.
 *    Matching ignores case, digit look-alikes (4 = a, 3 = e...) and repeated letters. Re-read when its
 *    mtime changes (checked at most every 30 s), no restart. Matches are replaced by ***. The same list
 *    refuses new account names (ApiGuard.checkName) and masks room titles in /api/rooms (not in the game).
 *  Counters (since start) in counters(), shown by GET /api/admin/metrics as "chat_filter"; also counted in
 *    the metrics series as chat_filter|<reason>.
 */
public final class ChatFilter {
    public static volatile ChatFilter instance;

    static final int MAX_MARKS = 2, MAX_RUN = 6, MAX_SYMBOLS = 10, MAX_LINKS = 1, LINK_MSGS = 2;
    static final long REPEAT_MS = 120_000L, LINK_WINDOW_MS = 300_000L;
    /** A word, or single letters split by . - _ or spaces ("t.o.n.t.o"), checked joined. */
    static final Pattern WORD = Pattern.compile(
        "(?<![\\p{L}\\p{N}@$])[\\p{L}\\p{N}@$]([.\\-_ ])[\\p{L}\\p{N}@$](?:\\1[\\p{L}\\p{N}@$])+(?![\\p{L}\\p{N}@$])|[\\p{L}\\p{N}@$]+");
    static final Pattern LINK = Pattern.compile(
            "(?i)(https?://|www\\.|discord\\.gg/|\\b[a-z0-9-]{2,63}\\.(com|net|org|gg|io|ly|me|xyz|ru|tk|ml|ga|cf|co|info|site|online|top|link|app|dev|tv|br|ar|es|mx|cl|pt)\\b)");

    private final Path file;
    private final long reloadMs;   // api.badwords_reload_s, default 30
    private volatile long fileStamp = -2, lastCheck = 0;
    private volatile List<String[]> words = List.of();   // {folded word, kind: "w" | "p" | "s"}
    private final Map<String, Object[]> last = new ConcurrentHashMap<>();   // "n:name" / "i:ip" -> {folded text, t}
    private final Map<String, long[]> links = new ConcurrentHashMap<>();    // key -> timestamps ring
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public ChatFilter(ServerConfig cfg) {
        file = Path.of(cfg.get("api.badwords_file", "data/badwords.txt"));
        reloadMs = Math.max(1, cfg.getInt("api.badwords_reload_s", 30)) * 1000L;
        reload(true);
        instance = this;
    }

    // ---- counters ----------------------------------------------------------------------------

    public void count(String reason) {
        counters.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
        Metrics.inc("chat_filter", reason);
    }

    public Map<String, Long> counters() {
        Map<String, Long> m = new TreeMap<>();
        for (Map.Entry<String, AtomicLong> e : counters.entrySet()) m.put(e.getKey(), e.getValue().get());
        m.put("badwords_loaded", (long) words.size());
        return m;
    }

    // ---- owner's word list -------------------------------------------------------------------

    /** Re-reads the list if the file changed (at most every 30 s unless forced). */
    public void reload(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastCheck < reloadMs) return;
        lastCheck = now;
        try {
            long stamp = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() + Files.size(file) : -1;
            if (stamp == fileStamp) return;
            List<String[]> out = new ArrayList<>();
            if (stamp != -1)
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    l = l.trim();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    String kind = l.startsWith("*") && l.endsWith("*") && l.length() > 2 ? "s" : l.endsWith("*") ? "p" : "w";
                    String f = squeeze(ff(l.replace("*", "")));
                    if (f.length() >= 2) out.add(new String[] { f, kind });
                }
            words = List.copyOf(out);
            if (fileStamp != -2 || !out.isEmpty()) System.out.println("chat filter: " + out.size() + " words from " + file);
            fileStamp = stamp;
        } catch (Exception e) {
            System.out.println("chat filter: cannot read " + file + ": " + e);
        }
    }

    /** fold() after removing accents ("pendéjo" = "pendejo"). */
    static String ff(String s) { return ApiGuard.fold(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)); }

    private static String squeeze(String f) { return f.replaceAll("(.)\\1+", "$1"); }

    private boolean bad(String token) {
        String f = ff(token);
        if (f.length() < 2) return false;
        String sq = squeeze(f);
        for (String[] w : words) {
            switch (w[1]) {
                case "w": if (sq.equals(w[0]) || sq.equals(w[0] + "s") || f.equals(w[0])) return true; break;
                case "p": if (sq.startsWith(w[0]) || f.startsWith(w[0])) return true; break;
                default:  if (sq.contains(w[0]) || f.contains(w[0])) return true;
            }
        }
        return false;
    }

    /** Text with the owner's words replaced by ***. */
    public String maskBadwords(String text) { return mask(text, true); }

    private String mask(String text, boolean countIt) {
        reload(false);
        if (words.isEmpty() || text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder();
        Matcher m = WORD.matcher(text);
        int at = 0;
        boolean any = false;
        while (m.find()) {
            out.append(text, at, m.start());
            String g = m.group();
            if (bad(g.replaceAll("[.\\-_ ]", ""))) { out.append("***"); any = true; } else out.append(g);
            at = m.end();
        }
        out.append(text.substring(at));
        if (any && countIt) count("badword_masked");
        return out.toString();
    }

    /** True if a player name (no spaces) contains a word of the owner's list. */
    public boolean nameHasBadword(String name) {
        reload(false);
        if (name == null || words.isEmpty()) return false;
        String f = ApiGuard.fold(name), sq = squeeze(f), bare = ApiGuard.fold(name.replaceAll("[0-9]+$", ""));
        for (String[] w : words) {
            if (w[1].equals("w") ? (sq.equals(w[0]) || f.equals(w[0]) || bare.equals(w[0]) || sq.equals(w[0] + "s"))
                                 : (sq.contains(w[0]) || f.contains(w[0]))) return true;
        }
        // whole-word entries of 4+ letters are also refused inside a name ("xXputoXx")
        for (String[] w : words) if (w[1].equals("w") && w[0].length() >= 4 && (sq.contains(w[0]) || f.contains(w[0]))) return true;
        return false;
    }

    /** Room title for the page: owner's words masked (the game keeps its own bytes). */
    public static String maskTitle(String title) {
        ChatFilter f = instance;
        return f == null || title == null ? title : f.mask(title, false);
    }

    // ---- characters --------------------------------------------------------------------------

    /** Clean text: see the class comment. Counts "chars_removed" and "caps_lowered". */
    public String sanitize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        int marks = 0, run = 0, prev = -1, symbols = 0;
        boolean removed = false;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            int type = Character.getType(c);
            if (Character.isWhitespace(c) || c == 0x00A0 || c == 0x3000) c = ' ';
            else if (type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || (c >= 0xFE00 && c <= 0xFE0F) || (c >= 0xE0000 && c <= 0xE007F) || c == 0x115F || c == 0x1160
                    || c == 0x3164 || c == 0xFFA0 || c == 0x2800) { removed = true; continue; }   // incl. "blank" letters
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK) {
                if (++marks > MAX_MARKS || b.length() == 0) { removed = true; continue; }
                b.appendCodePoint(c);
                continue;
            }
            marks = 0;
            if (c == ' ' && (b.length() == 0 || b.charAt(b.length() - 1) == ' ')) continue;
            run = c == prev ? run + 1 : 1;
            prev = c;
            if (run > MAX_RUN) { removed = true; continue; }
            if (type == Character.OTHER_SYMBOL || Character.isSupplementaryCodePoint(c) && !Character.isLetterOrDigit(c)) {
                if (++symbols > MAX_SYMBOLS) { removed = true; continue; }
            }
            b.appendCodePoint(c);
        }
        String r = b.toString().trim();
        if (removed) count("chars_removed");
        int letters = 0, upper = 0;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) upper++; }
        }
        if (letters >= 12 && upper * 10 > letters * 7) { r = r.toLowerCase(Locale.ROOT); count("caps_lowered"); }
        return r;
    }

    // ---- spam --------------------------------------------------------------------------------

    /** null if the message may go, else the reason ("repeated", "too many links", "link flood"). Counts it. */
    public String check(String name, String ip, String text) {
        long now = System.currentTimeMillis();
        if (last.size() > 20_000) last.values().removeIf(v -> now - (Long) v[1] > REPEAT_MS);
        if (links.size() > 20_000) links.values().removeIf(v -> now - max(v) > LINK_WINDOW_MS);
        String folded = squeeze(text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""));
        if (folded.isEmpty()) folded = text;
        String[] keys = { "n:" + (name == null ? "" : name.toLowerCase(Locale.ROOT)), "i:" + ip };
        for (String k : keys) {
            Object[] l = last.get(k);
            if (l != null && folded.equals(l[0]) && now - (Long) l[1] < REPEAT_MS) { count("repeated"); return "repeated"; }
        }
        int n = 0;
        Matcher m = LINK.matcher(text);
        while (m.find()) n++;
        if (n > MAX_LINKS) { count("too_many_links"); return "too many links"; }
        if (n > 0) {
            for (String k : keys) {
                long[] t = links.get(k);
                if (t == null) continue;
                int recent = 0;
                for (long x : t) if (now - x < LINK_WINDOW_MS) recent++;
                if (recent >= LINK_MSGS) { count("link_flood"); return "link flood"; }
            }
            for (String k : keys) {
                long[] t = links.computeIfAbsent(k, x -> new long[LINK_MSGS]);
                synchronized (t) { int oldest = 0; for (int i = 1; i < t.length; i++) if (t[i] < t[oldest]) oldest = i; t[oldest] = now; }
            }
        }
        for (String k : keys) last.put(k, new Object[] { folded, now });
        return null;
    }

    private static long max(long[] v) { long m = 0; for (long x : v) m = Math.max(m, x); return m; }
}
