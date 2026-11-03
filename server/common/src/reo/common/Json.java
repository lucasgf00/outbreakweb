/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import java.util.Collection;
import java.util.Map;

/** Minimal JSON writer (no dependencies). */
public final class Json {
    private Json() {}

    /** Text that is already JSON (written by this class before), inserted as is. */
    public static final class Raw {
        final String json;
        public Raw(String json) { this.json = json; }
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            str(sb, (String) o);
        } else if (o instanceof Raw) {
            sb.append(((Raw) o).json);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object x : (Collection<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else {
            str(sb, o.toString());
        }
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
