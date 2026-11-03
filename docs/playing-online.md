# How to play online (File #1 and File #2, on one PC)

2 to 4 players on the same computer, each in their own Chrome window, against a local server. No account or password needed.

## 1. Start everything (one command)

```
./run-local.sh
```

(From Windows with the code in WSL: `wsl -e bash -lc ~/reo-web/run-local.sh`.) Wait for `Ready. Open http://localhost:8620/`. It starts the lobby server, the network gateway and the web page. **Ctrl+C** stops everything. (If MariaDB does not start by itself, start the service and repeat.) It also prints the token for the owner's metrics panel at http://localhost:8620/admin.

## 2. The two windows

- **Player 1:** normal Chrome -> http://localhost:8620/ -> File #1.
- **Player 2:** an **incognito** window (Ctrl+Shift+N) -> same address.
- The page gives each player a random name ("Survivor" + 5 digits), already registered, so **Play** is enough. To change it: **Settings -> Online** (letters and digits, up to 15). If the name is taken, a red warning appears and **Play** stays blocked until you pick another (or go back to the previous one). If you change it while the game is running, it applies from the next **Restart**.
- The name is tied to that browser. In incognito the token is lost when the window closes, so next time use a different name for player 2.

**Three or four players:** each needs their own name, so each window needs its own storage: a normal window, an incognito window and, for the 3rd and 4th, **another Chrome profile** (profile icon top right -> Add) or Edge. All four go to the same address. If the machine is tight, lower the resolution in the game bar. The local gateway accepts up to 32 connections from this PC; if the network ever says **"Too many connections from your network"**, close old game tabs and wait 20 s (it reconnects by itself).

Default keys: **WASD** = d-pad, **J** = X (cross), **K** = O (circle), **Enter** = Start. **F1** shows the controls.

## 3. From the title screen to the area list

Both players, with the game window focused:

1. Title: **Enter**. In the menu, **Down** to NETWORK PLAY and **X**.
2. Unsaved-data warning: **X**.
3. If "Your Network Configuration file has been loaded... Press the X button" appears, **X**.
4. Done: the game connects by itself (network configuration, login, news and SERVER SELECT pass without input; it is always **UP TOWN / FREE MODE**). You land on the **area list**: **X** on **AREA 01** (or any area, the same one for both).

The automatic part happens once per session: after an error or a disconnect, the screens ask for buttons as usual.

## 4. The room

**Player 1 (creates):** **X** on a "Vacant" row -> **RULE** screen. **Up/Down** picks the row and **Left/Right** changes the value:
- **Room Title**: X opens the keyboard (if left empty, the room is named after you).
- **Password**: X opens the keyboard; type and **Start** confirms. A password makes the room private.
- **No. of Players**, **Waiting Time**, **Difficulty**, **Friendly Fire**.
- **Scenario**: the five File #1 scenarios (Outbreak, Below Freezing Point, The Hive, Hellfire, Decisions, Decisions). The joining player sees the scenario in MEMBER LIST and the game starts on that scenario for both.
- **Character**: your character.
- **Finish**: creates the room and you are in the waiting room.

**Player 2 (joins):** the room appears by itself in the list ("1/2P Join in"). **X** on it -> **MEMBER LIST**: there you see the scenario and pick your character with **Left/Right** (a character already taken cannot be chosen). **Down** to **Enter Room** and **X**. If it has a password, the game asks for it: type it, **Start** and **Yes**. Then **X** on **STANDBY**.

**Player 1:** when **2/2P** appears, **Left** to **START SCENARIO** and **X**. The game starts for both, on the chosen scenario. The room disappears from the list.

**Chat:** in the waiting room, **Triangle** opens the keyboard ("Soft keyboard"); **Start** sends. (The Triangle key is shown in F1.)

**Finding someone:** in the area list or room list, **Start** -> **Search for User** -> **ENTER A LOGIN NAME** (keyboard) -> **SEARCH** (buttons are navigated with **Left/Right**). It shows which area and room they are in. The game does not offer **MOVE**: to join, go back to the area list, enter that area and pick the room.

**What File #1 does not have:** changing character inside the waiting room (it is chosen in RULE or MEMBER LIST) and kicking players.

Open rooms: http://localhost:8620/api/rooms (title, players, maximum, whether private, and whether waiting or in game).

## Known issues

- **Room waiting time:** 5 minutes by default ("Left: ..."). When it runs out, create the room again.
- **Background tabs:** the game keeps running when the window is minimized or covered (verified at 60 fps). If the browser throttles it anyway, a warning appears in the bar.
- **Password:** the correct one gets you in. A wrong one shows **"Incorrect password. Entry forbidden."**, you return to the room list by yourself and can try again (X on the room, Down to Enter Room, X). Before 2026-10-18 the game hung; the fix is in the server (rebuild it with `scripts/setup.sh build` and restart `run-local.sh`). If the room is full or already started, the message is "You couldn't enter the room".
- **If someone closes the browser:** the server marks them disconnected after 30 s. If they owned a waiting room, the room disappears from the list and those inside see "The room has been disbanded by the host." and return to the list. If it happens mid-game, the others keep playing (the leaver's character disappears). Reloading the page with the same name closes the previous session.
- **News:** if the news page is empty, the game raises error D901 (run-local.sh sets a short text).
- **4 players:** tested natively with 4 instances in File #1 (J's Bar) and File #2 (wild things): all four in the room, START, and all four in game seeing each other move. Until 2026-10-20 with 4 players the 4th player's entry (black screen) and START (someone stuck in the waiting room) failed: these were server bugs, so rebuild (`scripts/setup.sh build`) and restart `run-local.sh`.
- **Returning to the room after a game:** if after finishing the scenario everyone returns to the waiting room and someone leaves, the room reappears in the list with a free slot and another player can join (fixed in the server on 2026-10-20; tested with simulated clients, not yet with a really finished game).
- **Lag:** the server relays each message in under 1 ms; the game sends positions a few times per second and each console interpolates them (as the original did). To make it less noticeable: keep the windows visible and at 60 fps.
- Not tested over the internet.
- Black screen or "Gateway disconnected" in the pre-menu: `run-local.sh` is not running.

## File #2

Same as File #1: `run-local.sh`, two windows (normal and incognito), http://localhost:8620/ -> **File #2**. The name and credential are **the same for both games** (the account is shared): if you already played File #1 with a name in that browser, it works as is.

1. Title: **Enter**, **Down** to **NETWORK PLAY**, **X**.
2. Unsaved-data warning: **X**.
3. Everything else happens by itself (network configuration, DNAS, the "CAPCOM Online User Agreement", login, news and the server list). You land on **AREA SELECT** (the map with 10 areas): **X** on **Free Area** (the same for both; the others are Beginners, Veterans, Survival, Nightmare, Infinity, Elimination, Showdown, Scenario Run and Lounge).
4. In the area lobby: **X** on **FREE** -> **ROOM LIST**.
   - **Create (player 1):** **X** on "Vacant" -> **RULE**: Room Title, Password (X opens the keyboard, **Start** confirms), No. of Players (2-4), Waiting Time, Difficulty, Friendly Fire, Movie Skippable, **Scenario** (wild things, underbelly, flashback, desperate times, end of the road, elimination 1-3, showdown 1-3), Character and **Finish**.
   - **Join (player 2):** the room appears in the list with its scenario ("1/2P Join in"); **X** -> MEMBER LIST (character with Left/Right), **Down** to **Enter Room**, **X**. With a password: type it, **Start**, **Yes**. A wrong one gives "Incorrect password. Entry forbidden." and you return to the list.
5. **Player 1**, with the room full (2/2P ... 4/4P): **Left** to **START A SCENARIO** and **X**. It starts for everyone.
6. **QUICK START** (the 5 doors to the right of FREE, one per scenario): quick **2-player** game. Pick a door and a character; if another player picks the same door with a **different** character, they enter your room and the game starts by itself. If you pick the same character you do not match (you keep waiting: **CANCEL** and pick another). For 3 or 4 players use FREE -> ROOM LIST.
7. **Cindy in wild things:** when wild things starts, a Cindy stands next to the zoo gate even if nobody picked her. This is not an online bug: she also appears playing solo; it is part of the scenario's opening scene.

Tested natively with two instances (room with and without password, wrong password, wild things game with replicated movement). With 4 players: tested natively (4-player room, START, all four at the zoo). Pending: test it in two Chrome windows.
