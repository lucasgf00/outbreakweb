package reo.us;

/**
 * Network difficulty (protocol.md §6.8): the start info byte +0x13 is "difficulty control" (0 Auto, 1 Fixed); with
 * Auto the game ignores the RULE difficulty (File #1 plays EASY). Replays the client's logic over the server data:
 * File #1 netwk 0x58A880 + getter 0x5DE270, File #2 netwk 0x5A3940 (descriptor table 0x625BD0) + getter 0x5F60E0.
 *   javac -cp build/reo-bioserver.jar -d OUT tests/RuleDifficultyTest.java && java -cp build/reo-bioserver.jar:OUT reo.us.RuleDifficultyTest
 */
public class RuleDifficultyTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    /** File #1: start info +0x13..+0x15 for a mode (0 SCENARIO, 1 FREE) and the RULE screen choices. */
    static byte[] f1Start(byte[] rule, int mode, int diffSel, int ffSel) {
        byte[] out = new byte[3];
        System.arraycopy(rule, 0xC + mode * 3, out, 0, 3);
        if (mode == 0) return out;
        out[1] = (rule[2] != 0 && rule[6] != 0) ? (byte) diffSel : rule[0xA];
        out[2] = (rule[3] != 0 && rule[7] != 0) ? (byte) ffSel : rule[0xB];
        return out;
    }

    /** Getter 0x5DE270 / 0x5F60E0: control != 0 ? difficulty : the "auto" byte (File #1: cleared to 0 = EASY). */
    static int gameDifficulty(byte[] start, int auto) { return start[0] != 0 ? start[1] : auto; }

    /** netwk 0x625BD0: start-info offset (+0x1D, from +0x13) and option values (+0x20 + 0x20*k + 0x1E) per descriptor. */
    static final int[] F2_OFF = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 1, 1, 0xE, 0xF, 0x10};
    static int f2Value(int desc, int opt) {
        if (desc == 8) return new int[] {0, 5, 10, 15, 20, 30, 50, 100}[opt];
        if (desc == 12) return opt + 2;
        return opt;   // prize (15) is 2 bytes, not used by the areas
    }
    /** File #2: 0x5A3940 over the items of a mode sub-record; sel = the shown rows' choices, in order (-1 = default). */
    static byte[] f2Start(byte[] f, int area, int mode, int[] sel) {
        byte[] out = new byte[0x12];
        int q = area * 0x1E4 + 0x16C + mode * 0x3C, n = f[q + 2], k = 0;
        for (int i = 0; i < n; i++) {
            int it = q + 3 + 4 * i, desc = f[it], opt = f[it + 1];
            if (f[it + 2] != 0) { int s = k < sel.length ? sel[k] : -1; k++; if (s >= 0) opt = s; }
            out[F2_OFF[desc]] = (byte) f2Value(desc, opt);
        }
        return out;
    }
    static int shownRows(byte[] f, int area, int mode) {
        int q = area * 0x1E4 + 0x16C + mode * 0x3C, shown = 0;
        for (int i = 0; i < f[q + 2]; i++) if (f[q + 3 + 4 * i + 2] != 0) shown++;
        return shown;
    }
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    public static void main(String[] a) {
        byte[] rule = UsAppService.rule();
        check(rule[0xC] == 1 && rule[0xF] == 1, "File #1 RULE.DAT: difficulty control Fixed in SCENARIO and FREE");
        for (int d = 0; d < 4; d++)
            check(gameDifficulty(f1Start(rule, 1, d, 0), 0) == d, "File #1 FREE: RULE difficulty " + d + " -> game " + d);
        check(gameDifficulty(f1Start(rule, 0, 3, 0), 0) == rule[0xD], "File #1 SCENARIO: fixed difficulty from RULE.DAT");

        byte[] f = UsAppService.f2Areas();
        int[] none = {};
        for (int area = 0; area < UsAppService.F2_AREAS.length; area++)
            for (int mode = 0; mode < 2; mode++) {
                int q = area * 0x1E4 + 0x16C + mode * 0x3C;
                byte[] out = f2Start(f, area, mode, none);
                boolean ok = f[q + 2] <= UsAppService.F2_RULE_MAX && out[0] == 1 && shownRows(f, area, mode) >= 2 && shownRows(f, area, mode) <= UsAppService.F2_RULE_SHOWN_MAX;
                if (!ok || mode == 0) check(ok, "File #2 area " + (area + 1) + " mode " + mode + ": control Fixed, "
                    + shownRows(f, area, mode) + " rows, start " + hex(out));
            }
        for (int d = 0; d < 4; d++)
            check(gameDifficulty(f2Start(f, 0, 0, new int[] {d}), 3) == d, "File #2 Free Area: RULE difficulty " + d + " -> game " + d);
        check(f2Start(f, 0, 0, none)[1] == 1 && f2Start(f, 0, 0, none)[3] == 1, "Free Area defaults NORMAL, movie On");
        check(f2Start(f, 1, 0, none)[1] == 0 && f2Start(f, 1, 0, new int[] {1})[1] == 1, "Beginners: EASY, NORMAL choosable");
        check(f2Start(f, 2, 0, none)[1] == 2 && f2Start(f, 2, 0, new int[] {1})[1] == 3, "Veterans: HARD, VERY HARD choosable");
        check(f2Start(f, 3, 0, none)[9] == 2 && f2Start(f, 3, 0, none)[4] == 1, "Survival: Life MAX 50%, virus 30%");
        check(f2Start(f, 4, 0, none)[5] == 1, "Nightmare: NIGHTMARE On (row locked)");
        check(f2Start(f, 5, 0, none)[6] == 1, "Infinity: INFINITY On (row locked)");
        check(f[4 * 0x1E4 + 0x16C + 3 + 3] == 0 && f[5 * 0x1E4 + 0x16C + 3 + 3] == 0, "NIGHTMARE/INFINITY rows not adjustable");
        check((f[6 * 0x1E4 + 0x14] & 0xFF) == 0xE0 && (f[7 * 0x1E4 + 0x15] & 0xFF) == 7, "Elimination 0xE0 / Showdown 0x700 scenarios");
        boolean bad = false;
        try { UsAppService.f2Rule("nightmare=maybe"); } catch (IllegalArgumentException e) { bad = true; }
        check(bad, "bad rule option rejected");
        check(UsAppService.f2Rule("infinity=On!").length == 2, "control Fixed added when absent");
        bad = false;
        try { UsAppService.f2Rule("difficulty=HARD, ff=On, movie=On, hint=On, life=50%"); } catch (IllegalArgumentException e) { bad = true; }
        check(bad, "a 5th shown row rejected (RULE screen fits 4)");
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all ok");
    }
}
