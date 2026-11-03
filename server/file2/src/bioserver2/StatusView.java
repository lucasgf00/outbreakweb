/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.

    NOTE: file1/src/bioserver1/StatusView.java and file2/src/bioserver2/StatusView.java
    are identical except for the package line. Edit both.
*/

package bioserver2;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import reo.common.GameConfig;
import reo.common.LobbyView;

/**
 * Read-only snapshot of areas, rooms, slots and players for the HTTP API.
 * Never exposes login ids, session ids or passwords: players are identified
 * by their in-game handle (6 chars) and nickname.
 */
public class StatusView implements LobbyView {
    private static final Charset SJIS = Charset.forName("Shift_JIS");
    private static final int AFTERGAME_AREA = 51;   // upstream: area 51 = post-game lobby

    private final GameConfig cfg;
    private final PacketHandler ph;

    public StatusView(GameConfig cfg, PacketHandler ph) {
        this.cfg = cfg;
        this.ph = ph;
    }

    @Override
    public GameConfig config() {
        return cfg;
    }

    private static String slotStatus(byte s) {
        switch (s) {
            case Slot.STATUS_DISABLED: return "disabled";
            case Slot.STATUS_FREE: return "free";
            case Slot.STATUS_INCREATE: return "creating";
            case Slot.STATUS_GAMESET: return "gameset";
            case Slot.STATUS_BUSY: return "full";
            default: return "unknown";
        }
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("<BR>", " ").replaceAll("<[^>]*>", "").trim();
    }

    private static Map<String, Object> player(Client c) {
        Map<String, Object> m = new LinkedHashMap<>();
        HNPair hn = c.getHNPair();
        m.put("handle", hn == null ? null : new String(hn.getHandle(), SJIS));
        m.put("nickname", hn == null ? null : new String(hn.getNickname(), SJIS));
        byte[] st = c.getCharacterStats();
        m.put("character", (st == null || st.length <= 0xc8) ? null : (st[0xc8] & 0xff));   // upstream Client: stats[0xc8] = character
        if (c.getSlot() != 0) {
            m.put("player_number", (int) c.getPlayerNum());
            m.put("host", c.getHostFlag() == 1);
        }
        return m;
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", cfg.id);
        g.put("title", cfg.title);
        g.put("protocol", cfg.protocol);
        g.put("lobby_port", cfg.lobbyPort);
        g.put("game_port", cfg.gamePort);

        Areas areas = ph.getAreas();
        Rooms rooms = ph.getRooms();
        Slots slots = ph.getSlots();
        if (areas == null || rooms == null || slots == null) {
            g.put("ready", false);
            return g;
        }
        g.put("ready", true);

        @SuppressWarnings("unchecked")
        List<Client> clients = new ArrayList<>(ph.getClients().getList());   // CopyOnWriteArrayList snapshot
        int inGame = 0;
        Map<Integer, Integer> perGame = new TreeMap<>();
        GameServerPacketHandler gs = ph.getGameServerPacketHandler();
        if (gs != null) {
            @SuppressWarnings("unchecked")
            List<Client> gcl = new ArrayList<>(gs.getClients().getList());
            inGame = gcl.size();
            for (Client c : gcl) perGame.merge(c.gamenumber, 1, Integer::sum);
        }
        g.put("players_lobby", clients.size());
        g.put("players_in_game", inGame);

        List<Object> areaList = new ArrayList<>();
        for (int a = 1; a <= areas.getAreaCount(); a++) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", a);
            am.put("name", areas.getName(a));
            am.put("description", strip(areas.getDescription(a)));
            am.put("active", areas.getStatus(a) != Area.STATUS_INACTIVE);
            List<Object> areaPlayers = new ArrayList<>();
            for (Client c : clients) if (c.getArea() == a && c.getRoom() == 0) areaPlayers.add(player(c));
            am.put("players", areaPlayers);

            List<Object> roomList = new ArrayList<>();
            for (int r = 1; r <= rooms.getRoomCount(); r++) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id", r);
                rm.put("name", rooms.getName(a, r));
                rm.put("active", rooms.getStatus(a, r) != Room.STATUS_INACTIVE);
                List<Object> roomPlayers = new ArrayList<>();
                for (Client c : clients) if (c.getArea() == a && c.getRoom() == r && c.getSlot() == 0) roomPlayers.add(player(c));
                rm.put("players", roomPlayers);

                List<Object> slotList = new ArrayList<>();
                for (int s = 1; s <= slots.getSlotCount(); s++) {
                    Slot slot = slots.getSlot(a, r, s);
                    List<Object> sp = new ArrayList<>();
                    for (Client c : clients) if (c.getArea() == a && c.getRoom() == r && c.getSlot() == s) sp.add(player(c));
                    if (slot.getStatus() == Slot.STATUS_FREE && sp.isEmpty()) continue;   // only open/used slots
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("id", s);
                    sm.put("name", new String(slot.getName(), SJIS));
                    sm.put("status", slotStatus(slot.getStatus()));
                    sm.put("scenario", (int) slot.getSscenario());
                    sm.put("password", slot.getProtection() == Slot.PROTECTION_ON);
                    sm.put("max_players", (int) slot.getRuleSet().getNumberOfPlayers());
                    sm.put("game_number", slot.gamenr);
                    sm.put("players", sp);
                    slotList.add(sm);
                }
                rm.put("slots", slotList);
                roomList.add(rm);
            }
            am.put("rooms", roomList);
            areaList.add(am);
        }
        g.put("areas", areaList);

        List<Object> agl = new ArrayList<>();
        for (Client c : clients) if (c.getArea() == AFTERGAME_AREA) agl.add(player(c));
        g.put("after_game_lobby", agl);

        List<Object> games = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : perGame.entrySet()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("game_number", e.getKey());
            gm.put("players", e.getValue());
            games.add(gm);
        }
        g.put("games_in_progress", games);
        return g;
    }
}
