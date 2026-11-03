# Mods, cheats and cut content: US version (SLUS-20765 / SLUS-20984)

Read-only web research, 2026-10-27. Nothing was registered for, published, or contacted.
Tags: **[FACT]** = stated by the linked source or measured here. **[HYPOTHESIS]** = our deduction or an unconfirmed rumor.

**Sources that could not be read** (403, 500 or 402): tcrf.net (the Outbreak pages returned only text aimed at automated agents, with no content; ignored), wiki.pcsx2.net, gamehacking.org, supercheats, gamefaqs, the the-horror.com threads, and the residentevil.fandom web pages (those were read through the MediaWiki API).
**obsrv.org was not opened**, per project rules. It is cited only because the fandom wiki references it.

## 0. Which version we have [FACT, measured on our disc images]

| Game | SYSTEM.CNF | ELF CRC (PCSX2 style) |
|---|---|---|
| File #1 `SLUS_207.65` | `VER = 2.00` | `0245EF6D` |
| File #2 `SLUS_209.84` | `VER = 1.05` | `1E65A50E` |

This matters because codes and patches depend on the version. Two US versions of File #1 circulate: the PCSX2 pnach files cover `02445B50` ("Version 1.01") and `0245EF6D` ("VER = 2.00"), and Almar splits its codes into "Version 1.0" and "Version 1.1".
- [HYPOTHESIS] Our VER 2.00 corresponds to Almar's "Version 1.1". Needs confirming by testing a harmless code.

## 1. Mods, patches and hacks for the US version

- **Widescreen 16:9 (PCSX2), for both games and exactly our CRCs.** In the official repo [PCSX2/pcsx2_patches](https://github.com/PCSX2/pcsx2_patches) [FACT]:
  - `patches/SLUS-20765_0245EF6D.pnach`: 23 `patch=` lines. Credits: "FMV's fix by nemesis2000".
  - `patches/SLUS-20984_1E65A50E.pnach` (and `_1E65A59B`): 11 lines. Credits: "gameplay by Hyakki", "item fix by nemesis2000", "FMV's fix by nemesis2000".
  - `SLUS-20765_02445B50`, the other version, only carries `gsaspectratio=16:9` with no code.
  - Format: pnach, i.e. 32-bit writes at EE addresses. The repo has **no license** (the GitHub API gives `license: null`).
- **"3 AI characters" for File #2 (US and JP).** A the-horror.com thread ([link](https://www.the-horror.com/forums/forum/general/umbrella-labs/modding-forum/5942-present-outbreak-file-2-3-ai-characters-us-jp/page4)). [FACT] only the title, seen in the search engine. The content could not be read (error 500), so author, format and status are unverified.
- **Native PC port of File #2 by reverse engineering (Fothsid).** Makes a 60 fps demo and **will not be released** ([DSOGaming](https://www.dsogaming.com/videotrailer-news/someone-is-reverse-engineering-resident-evil-outbreak-file-2-on-pc/)) [FACT according to the article].
- **US File #2 prototype "pub Version 1.02 Offline Only Review 2005-01-14"** at [Hidden Palace](https://hiddenpalace.org/Resident_Evil:_Outbreak_-_File_2_(Jan_14,_2005_prototype)): "no obvious visible differences". Not useful as a mod [FACT].
- No texture packs, free camera, 60 fps or documented debug mode were found for the US version.
  - [FACT] negative: the searches turned up nothing. [HYPOTHESIS] they may exist in unindexed Discords.

## 2. Cheat codes for the US version

- **File #1 US, CodeBreaker (encrypted):** [Almar's Guides v1.1](https://almarsguides.com/retro/walkthroughs/ps2/games/ResidentEvilOutbreak/CodeBreaker/Version1.1/) and [v1.0](https://almarsguides.com/retro/walkthroughs/ps2/games/ResidentEvilOutbreak/CodeBreaker/Version1.0/), with subpages "Collection Codes" and "Unlock Costume Codes" [FACT]. They include:
  - master code
  - Unlock Infinity Mode / All Scenarios / All NPCs
  - infinite health, ammo and items; infinite time
  - 1-hit kills, no damage
  - no zombification; 100% infection with L1+Select
  - The pages give no author.
- **File #2 US:**
  - "Resident Evil Outbreak File #2 code database" on the-horror.com ([thread 704](https://www.the-horror.com/forums/forum/general/umbrella-labs/704-resident-evil-outbreak-file-2-code-database)). According to the search-engine excerpt [FACT, secondhand]: it has a *player character modifier* for each of the 4 players, where the last byte is the character ID, plus a "character type" code. It is compatible with CodeBreaker v1-9 and credits "JARNOLD83 & VIRUSPUNK".
  - GameHacking.org has an NTSC-U entry ([game/96426](https://gamehacking.org/game/96426)) and a separate JP one. They could not be read.
- **Codes are per region:** NTSC, PAL and JP have different offsets (a GameFAQs thread quoted by the search engine).
- **On whether they are freely published:** yes, on public pages with no registration. An address and a value are data (facts).
  - CodeBreaker v7+ codes are encrypted. To get the raw address they must be decrypted with an Omniconvert-style tool, and that is our own work.
  - Note: `network.md` records that we did not find the health/virus address. Decrypting "Infinite Health" or "Don't become a zombie" for the matching version would give it to us.
- **Legitimate in-game unlocks, no cheats** [FACT, CheatCC, Wikipedia]:
  - Infinity Mode: with 100% and 50,000 points in File #1.
  - Luke and other NPCs are bought in the collection.
  - "Data Convert" transfers data between File #2 and File #1 (fandom, Luke page).

## 3. "File 3" and cut content

**What "File 3" is** [FACT, fandom "NETWORK BIOHAZARD", read through the API]: it is not a game or a mode. Fans use the name for content that was cut from *Network Biohazard / Resident Evil Online*.
- The original project had more than 41 planned scenarios and was split into File #1 and File #2.
- [Unseen64](https://www.unseen64.net/2009/10/26/resident-evil-outbreak-ps2-beta/) says a third game was planned and never released. Wikipedia puts it this way: 18 scenarios in October 2002, 5 in May 2003, and the other 5 moved to File #2.

**What was left inside the games and can be reached:**
1. **Hidden characters ("File 3 characters")** [FACT]. Fandom: "Using cheat devices such as the Game Shark (or .Pnach/patches via PCSX2, or hacked saves) on Outbreak and File #2, people are able to hack/unlock some hidden character skins".
   - The source it cites is a page on the obsrv.org wiki (not opened). Unseen64 confirms the models "are still inside the two outbreak games … using Gameshark codes".
   - Fandom list:
     - **U.S.S.:** HUNK (with and without mask), Derek, Miguel, Conrad, Rodriguez. Luke is already purchasable.
     - **U.B.C.S. / Docks:** Billy, Clint, Mac (MacDowell), Matt.
     - **Civilians:** Bone, Prof. Frank, Mickey, Keith, Gary, Jake, Kate, Matthew, Mary, Rodney, an unnamed zombie woman and child, and the "1F".
     - **"Zombie templates":** Amelia, Chuck, Don, Dr. Ethan/Howard/Isaac, Ginger, Laura, Nicolas, Philip, Robert, Roger, Richard.
   - Mechanism [FACT, fandom]:
     - Each character occupies a "hex slot". For example, the "1F" is slot `0x1F`.
     - It is forced with the "character modifier" code together with a "type" (Kevin type, David type, etc.).
     - Several have no stats of their own: the 1F is "statless" and stats must be injected. Bone is Kevin type and starts with mixed herb.
   - Version: both games. [HYPOTHESIS] Also JP, because the codes exist for each region; the IDs are probably the same and the addresses different.
2. **Unused enemies** [FACT, fandom, no references of its own]: G-Dog (E02_00), baby spider (E08), one similar to the Grave Digger (E21), G-Birkin (E28_00), E31_00, unknown Tyrant (E42_00), RE2-style Licker (HE00_01), carnivorous plant, Grub, giant burnt Neptune, Thanatos variants. Accessed by "hacking the data", no published method.
3. **Zones** [FACT, fandom]:
   - The Hive hospital door was left "open" in the data, but the room was deleted: forcing it hangs the game.
   - There is a variant of "In front of Apple Inn" with a second open sewer.
   - Remnants of the west side of the zoo (Wild Things) remain.
   - A boiler room appears only in the violence warning.
   - Unreleased scenario IDs: r000, r004, r006, r016, r019, r024 (motel "A Day in Raccoon"), r039 (Docks/UBCS). No evidence they are playable: [HYPOTHESIS] loose textures or remnants remain, not complete scenarios.
- **TCRF:** its page could not be obtained (see above).

## 4. Translations and language patches

- **English for the JP versions (File #1 and #2)** [FACT]: [SilentHeII/Outbreak-Translation](https://github.com/SilentHeII/Outbreak-Translation), by REfan988 and Silent Hell.
  - Version 2.1 of 2020-12-08, with map names translated thanks to Fothsid. License **GPL-3.0**. Distributed as `Patches.7z` over redump ISOs of the JP versions.
  - v2.0 adds a half-width English character mode on the "virtual keyboard", toggled with L2.
- **Spanish for the JP File #1** ("Biohazard Outbreak Spanish Patched") [FACT, according to [RetroGameTalk](https://retrogametalk.com/repo/ps2-iso/biohazard-outbreak-spanish-patched/) as quoted by the search engine]:
  - General translation by AEMT; secondary work by Sebters, Venom Snake and Hoar22.
  - v1.2 of 22 October 2025, with known faults: X/O swapped in some menus and spelling errors. License: not found.
  - We do not know whether a File #2 one exists.
- **No Spanish or Portuguese translations of the US versions were found.**
- **Are they portable to the US version? Not directly** [well-founded HYPOTHESIS]:
  - They are built on a different ELF and different data files. Text tables, fonts and offsets belong to the JP version.
  - They are also third-party work: AEMT's Spanish text is theirs. Only the *idea* would be reusable without permission. We would have to translate ourselves on the US text format and check whether the font has ñ and accents.

## 5. What the JP version has that the US does not (brief)

[FACT, unless marked otherwise]
- **Network:** KDDI MMBB instead of the Sega Network Application Package. The US lost private messages, advanced search and special filters ([Wikipedia File #2](https://en.wikipedia.org/wiki/Resident_Evil_Outbreak:_File_2)).
- **Text:** a virtual keyboard for typing.
  - [FACT] that it exists, from the translation patch's README.
  - [HYPOTHESIS] that it is used for lobby or room text chat. Not confirmed, and neither version has voice.
- **JP File #2 keeps the ad-libs** (spoken phrases) that the US removed: in the US the character only speaks with the stick or on request. It also has Japanese subtitles in cutscenes. A minor detail: "Rinda" for Linda.
- **Same characters and scenarios:** the Steam REOOPC community plays JP for the network and chat, not for extra content.
- **Dates:** JP came out first (File #1 on 2003-12-11, File #2 on 2004-09-09) and its servers closed on 2011-06-30. Wikipedia gives two US shutdown dates in 2007: March 31 and December 31.

## What we can do ourselves

| Idea | Effort | Risk | Permission? |
|---|---|---|---|
| **16:9 widescreen** for both (a pre-menu option): our own patch for the projection and the item/FMV cropping, plus 16:9 output in the renderer. The PCSX2 pnach files say it *can be done* and for which CRC, but we do not copy them. | Medium | Low (offline); online it is client-only | **No** if we write it ourselves. **Yes** to copy the Hyakki/nemesis2000 ones (unlicensed repo) |
| **Unlock everything** (collection, scenarios, NPCs, Infinity Mode, costumes) with a PS2_POKE on the save flags in RAM or on the memory card. Addresses from our own search or from decrypting the public CB codes (data). | Low-medium | Low. Online it gives an advantage: limit it to offline or make it a server rule | No |
| **"File 3" characters** (HUNK, Bone, Clint, etc.) as an offline option: write the slot ID and the "type". Inject stats for those that have none. | Medium | Medium: incomplete models (1F), hangs, and online the other clients must see the same ID | No (the IDs are data) |
| **Offline cheats** (health, ammo, no virus). Also gives us the health/virus address missing in `network.md`. | Low once decrypted | Low | No |
| **Recover the ad-libs in US File #2**, if the audio is still on the disc or by reusing File #1 US's. | High | Medium | No (bytes from the user's own game; not published) |
| **Our own text chat** in the web layer (pre-menu overlay and the server), instead of porting the JP keyboard. | Medium | Low | No |
| **Our own Spanish translation of the US versions.** | High (text format and font) | Medium | No if we do it ourselves; **Yes** to use AEMT's or derive from the GPL-3 patch |
| **Unused enemies or zones** (G-Birkin, RE2 Licker, Hive hospital). | High | High (deleted room = hang) | No |
| **File #2 "3 AI" mod.** | Unknown | Medium | **Yes** if reused; reimplementing it: no |

## 6. Migrate to the JP version to use community mods? (2026-10-27)

**Conclusion: no.** [well-founded HYPOTHESIS, see reasons]
- **JP has no more mods than the US** [FACT, negative; English and Japanese searches]: only translations (English GPL-3; Spanish F1 by AEMT) and the same per-region cheat codes. No custom campaigns or scenarios were found (the only "File 3" mod is an unfinished concept for GTA San Andreas, [ModDB](https://www.moddb.com/mods/resident-evil-outbreak-file-3)). The Steam REOOPC community only maintains JP servers, with no new content.
- **What the project owner wants is already on the US side:** [RaccoonRecomp/REO-Cheats](https://github.com/RaccoonRecomp/REO-Cheats) [FACT] is a cheat mod for another recompilation project ("Outbreak Recompiled"), for **File #1 US SLUS-20765 VER 2.00, our exact version**. 102 cheats: unlock scenarios/collection/Infinity, costumes for all 8, infinite health/ammo/time, player 1 model and type, number of players. 9 do not work on VER 2.00 (among them CodeBreaker "Unlock All Scenarios"; the MadCatz one works) and 19 costumes could not be decrypted. The data comes from GameHacking.org. License: it has a LICENSE, but the type could not be read. No code is copied; the addresses are data and can be re-derived.
- **Cost of migrating:** we have no JP disc image; overlays/patches/addresses for 2 games would have to be redone; Japanese text (the translations are third-party); it would split the ~1000 active US players; all the network/stability work of recent weeks is tuned to the US version.
- **Could not be read:** the obsrv.org wiki (403), the "3 AI characters" thread (500), TCRF, GameHacking.org.

Suggested next step: confirm which Almar version our File #1 VER 2.00 equals. Then decrypt a harmless code (for example "Unlock All Scenarios"), test it with PS2_POKE in an offline run and document it here.
