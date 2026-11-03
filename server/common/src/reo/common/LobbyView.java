/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
*/

package reo.common;

import java.util.Map;

/** Read-only view of one game's lobby state, used by the HTTP API. */
public interface LobbyView {
    GameConfig config();

    /** JSON-able snapshot (Map/List/String/Number/Boolean/null). Must not expose login ids or passwords. */
    Map<String, Object> snapshot();
}
