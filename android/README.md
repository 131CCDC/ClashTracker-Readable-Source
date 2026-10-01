# ClashTracker (Android / rooted tablet)

The on-device port of the Windows ClashTracker. It shows, in a floating system
overlay during a Null's Royale battle:

- opponent elixir, one decimal, like the Windows HUD
- four opponent hand cards with bundled art, zh-CN names, the probe's own
  selected cost and native `EVO` / `READY` / `★` marks
- an affordability gauge on those four tiles: a card the opponent cannot pay for
  yet is grey, and a darker clock sector covers the part of the cost that is
  still missing, receding clockwise from 12 o'clock as the elixir comes back
  (see below)
- the probe's native `NEXT` cycle (first tile highlighted) and a conservative
  recent-play row
- Ghost Drop: a touch-through card marker and placement square for a pending
  enemy PlayCard, before its execute tick, with both opacities adjustable from
  the settings screen while the overlay is running
- probe / connection state, and the identity the frame was read from

```text
Null's Royale  ->  existing native probe  ->  127.0.0.1:26888 (GET / PING)
               ->  Kotlin ProbeClient     ->  BattleState
               ->  OverlayService (TYPE_APPLICATION_OVERLAY)

Null's Royale  ->  native probe GHOST feed (50 ms)
               ->  GhostState -> full-screen FLAG_NOT_TOUCHABLE marker layer
```

No PC, bridge, MuMu, BlueStacks, scrcpy, Python, ADB forwarding or emulator is
involved at runtime. The app does not need root itself; only the game does,
because the probe is loaded as the game's `libscid_sdk.so`.

## Enemy hand affordability (the grey face and the clock sector)

The Android port of the Windows HUD's gauge, from the same rules
(`tracker/card_affordability.py` -> `CardAffordability.kt`). The four opponent
hand tiles are also a live elixir gauge, and a card the opponent cannot pay for
is drawn in **two layers**, the way the game's own hand reads:

1. **the grey face** - as long as `enemy elixir < cost`, the card art keeps its
   shape but loses its colour (desaturated to luma, then flattened);
2. **the dark sector** - on top of it, a darker translucent grey sector covers
   the share of the cost that is *still missing*, `missing = 1 - elixir / cost`,
   as a clock face that starts where the paid-for sweep ends and closes at 12
   o'clock. It shrinks clockwise as the elixir comes back.

So the four tiles are never just "grey": 2 elixir against a 4, 5 and 7 cost hand
keeps 50%, 60% and 71% of the face dark, and the moment a card is affordable its
sector is gone *and* its colour is back. The exact number stays next to the
droplet - the gauge is the glanceable version of it, not a replacement, and no
extra number is added to any tile.

- it applies to the **four current hand cards only**. `NEXT` and `刚出` tiles keep
  their plain art: a card behind the hand is not castable at any price, so
  greying it would be a lie.
- `progress = clamp(elixir / effectiveCost, 0, 1)` and `ready = elixir >= cost`
  are computed from the **raw probe elixir** (`OverlayModel.elixirValue`), never
  from the one-decimal text. `4.98` against a 5-cost card prints as `5.0` and is
  still *not* ready; the sweep step is floored, so a not-ready card can never
  paint its last sliver of grey away. The raw elixir is a float and drives the
  sector directly - nothing is rounded on the way in.
- **effective cost** is the probe's own `card_runtime[].selected_cost`, falling
  back to the bundled `cards.json`, exactly like the cost badge. A cost <= 0 is
  always castable; an unknown cost is *not* a claim - the tile keeps its normal
  art and gets no sector. The probe was not changed for this.
- **animation**: elixir coming back is interpolated over ~120 ms so the sector
  glides between probe frames and lands exactly on zero, while elixir being
  *spent* and a card *entering the hand* snap on the spot. Readiness itself is
  never animated: a tile can be READY while the sector is still finishing its
  travel.
- **how it is drawn**: the grey face is the *same* decoded bitmap through a
  `ColorMatrixColorFilter` (desaturated to luma, then darkened and flattened:
  `out = luma * 0.736 + 10.2`), and the sector is one
  `drawArc(..., useCenter = true)` in a flat `#9E0B0E14` ink, both clipped to the
  card face. One clip, one `drawBitmap` and one arc per grey tile; no path is
  rebuilt, no bitmap is re-encoded and nothing is allocated per frame. A READY
  tile takes the plain single-draw path, so an all-colour hand costs exactly what
  it did before.
- **layering**: the sector is painted *under* the `EVO` / `READY` / `★` badges,
  the cost badge and the name band, so the information on a tile is never greyed
  by the gauge - and it is clipped to the card face, never to the panel cell.
- **frames**: the sweep is the only thing that moves on its own. `onDraw` asks
  for the next frame (`postInvalidateOnAnimation`) only while some tile is still
  travelling, so a settled HUD schedules nothing at all.

`CardAffordability.kt` deliberately has no Android imports, so every rule above
is covered by plain JVM unit tests (`CardAffordabilityTest.kt`, 25 cases,
including the same acceptance cases A-F as the Windows suite plus the sector
geometry and ink).

The Windows HUD still paints the older single-layer look (a colour wedge cut out
of the grey), so the two clients differ in appearance until that one is brought
across; the arithmetic underneath them is the same file-for-file port.

## Ghost Drop opacity

The marker's two alphas are settings, not constants. `ClashTracker`'s own
settings screen carries two sliders - **Ghost Drop 牌面不透明度** (the card face,
the one that matters) and **Ghost Drop 落点框不透明度** (the thin placement square)
- both spanning 0..100%, both normalised to `0.0 .. 1.0` internally.

| behaviour | how |
| --- | --- |
| live while dragging | the slider writes the value, then hands `OverlayService` an `ACTION_GHOST_OPACITY` intent; the service repaints the marker layer and touches nothing else |
| no restart, no new battle | that intent never reconnects the probe, never resets the battle and never clears the pending markers, so a marker already on the arena picks the new alpha up on its next frame |
| persists | `ghost_card_opacity` / `ghost_tile_opacity` in the app's existing `SharedPreferences` file - the same store as the endpoint, the seat and the panel placement. No second config file |
| one source of truth | `SettingsStore` reads, the settings screen shows and `GhostLayerView` paints the same `GhostOpacity`; there is no hard-coded alpha left in the renderer |
| defaults | `0.65` for the face (`166 / 255`, the value the renderer used to hard-code) and `0.22` for the square (`56 / 255`), so an existing install looks unchanged until the slider is moved |
| out-of-range / NaN | clamped on read and on write (`GhostOpacity.of`), never a vanished or arena-covering marker |

0% is fully transparent and 100% fully opaque on both dials, and the two are
independent: making the face solid must not make the faint square solid.
`GhostOpacity.kt` has no Android imports either, so the range, the defaults and
the byte conversion are covered by `GhostOpacityTest.kt`.

Everything else about Ghost Drop is untouched: the real card face, the semantic
PlayCard target, the icon above the cell, the single-tile square, no text,
click-through, removal once the real deployment appears, several pending markers
at once, and the same enemy-ownership / event-key / projection rules.

## Probe reuse

Nothing in this app re-derives game state. It speaks the protocol already
implemented in `probe/nulls_probe.cpp`:

| command | response |
| --- | --- |
| `GET\n` | one JSON line (`nulls-live.v3` while in battle, `{"in_battle":false}` otherwise) |
| `PING\n` | `PONG\n` |
| `ARM\n` / `ATTACH\n` / `RESET\n` | lifecycle gate only; **not** used by this app |

Fields consumed, per `players[]` entry: `owner`, `accountId`, `elixir`,
`hand[].slot`, `hand[].card_id`, `hand[].name`, `cycle[]`, `deck[]`,
`card_runtime[]` (`card_id`, `active_form`, `selected_cost`,
`evolution_progress`, `variants[form_code=1].cycle_required`) and
`ability_runtime[]` (`ability_name`, `button_state`, `cooldown_ms`, `charges`,
`max_charges`), plus top-level `in_battle`, `tick`, `status`. Identity
additionally reads `client_input_runtime` (`hook_ready`, `events[].tick`,
`events[].command_type`) and `entities[]` (`id`, `owner`, `card_id`); see
"Which seat is the opponent?". The evolution/hero semantics and the
usable-button rule mirror `tracker/probe_client.py` exactly.

The APK packages the same generated `assets/cards/cards.json` and card PNGs as
the Windows HUD. It therefore works offline and keeps names, costs and art in
sync with that client. The probe name and `Unknown(<card_id>)` are safe fallbacks.

### Which seat is the opponent?

`players[].owner` is a **world seat index, not a view-relative one**: the server
picks the seat per match. In the captured corpus
(`cmd_audit/ghost-runs/run2/frames-A.jsonl`) the same tablet sat at seat 1 for
5344 battle frames and at seat 0 for 2666 frames of one session, so a "the local
player is seat 0" rule shows the local hand roughly half of the time.

Identity is an **account id**, exactly like the Windows HUD, which is told the id
once through `settings.local.json` and never misidentifies. The tablet resolves
it in this order:

1. the account id typed into the settings, when it is one of this battle's two
   players;
2. the account confirmed by this device's own local-input evidence
   (`probe/deployment_timing.inc` publishes one edge per play-card command
   submitted through the local UI). A decoded GHOST event whose `server_tick` is
   an edge tick is this device's own play — 63 of 63 local commands matched
   exactly and 0 of 7 opponent commands did — so its `issuer` is local. With
   Ghost Drop switched off, the seat vote is used instead: each edge votes once
   for the seat whose card deployment appears 20–24 ticks later (84 local versus
   10 opponent spawns in that window). Both signals come from the same play, so
   when they name the same account the identity locks on that first play; a
   single signal needs its own stricter threshold (2 exact matches, or 3 seat
   votes with a 2x margin) before it is cached for later battles;
3. a previously confirmed account cached on the device, when it is in the
   current battle's two-player table;
4. the cached local deck, matched against both players' `deck[]` (unique match
   only), which identifies the device even under a different account;
5. the configured seat as the provisional answer — drawn from the first frame so
   the panel is never empty — labelled `未确认·按座位 N` in amber.

The panel therefore renders from the first frame of a battle, and the footer
always names the identity it used: grey when proved, amber when it is only a
guess. The settings screen lists the two account ids of the live battle as
one-tap candidates, which pins the identity before the first play of the first
battle. `self_hint` is deliberately unused: the live audit found both clients
recording the same first issuer.


## Probe changes made for this app

`probe/nulls_probe.cpp` was changed in exactly two places:

1. `tcp_server_thread` binds `INADDR_LOOPBACK` instead of `INADDR_ANY`, so the
   endpoint is not reachable from the Wi-Fi/LAN. Hands, elixir and battle state
   never leave the device.
2. Each `hand[]` entry also carries `"name"`, read through the existing guarded
   `asset_name()` helper. This is additive; the existing PC bridge parser and
   all offsets are untouched.

No offset, hash check, build check or lifecycle rule was modified.

The pinned historical probe (`PROBE_SHA` in `tools/install_probe.py`) still
works with this app over `127.0.0.1` — it just leaks the endpoint to the LAN and
has no card names. Installing the rebuilt probe is recommended.

## Build

```powershell
# ANDROID_HOME must point at an SDK with platform 34 + build-tools 34.0.0
.\gradlew :app:assembleDebug :app:testDebugUnitTest
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

The rooted on-device installer is built at
`installer/build/outputs/apk/debug/installer-debug.apk`.

## One-time install on the rooted device

1. Copy and install both APKs: the Root Installer and ClashTracker.
2. Open the Root Installer and grant it Superuser access in Magisk / KernelSU /
   APatch (USB debugging and MTP do not grant Android apps root), run **Preflight**,
   then **Install**. It refuses an unknown `libg.so` before writing.
3. Force-stop and reopen Null's Royale so the replacement SDK is loaded.
4. Open ClashTracker, grant overlay and notification permission, then start the
   overlay. The account id and seat fields are optional: this device's first
   card play identifies it, and the result is cached for later battles.

ADB remains an optional install transport only; no PC or ADB connection is
needed after both APKs are on the tablet. **Restore** in the installer puts the
original SDK back.

`tools/install_probe.py` already verifies the ARM64 ELF, the pinned `libg.so`
SHA-256, the SDK backup identity and the post-install checksum, and it refuses
to touch an unrecognised build. It is unchanged by this work.

## Using it

1. Open the app, grant **overlay permission**, confirm the endpoint.
2. Leave Ghost Drop enabled, set **Ghost Drop 牌面不透明度** (and, if wanted, the
   placement square's) with the sliders, then tap **Save settings**. The sliders
   act immediately, including on markers already on the arena, and are written to
   the preferences as they move - the Save button is not required for them. My
   seat / account id are optional: the panel draws from the first frame
   regardless, and the first card play of a battle identifies this device.
   Tapping one of the two candidate account ids shown in the settings pins it
   before any play.
3. Tap **Start overlay**; `Test probe (PING)` should report `PONG`.
4. Open Null's Royale and play a battle. The panel shows
   `Probe: Connected` + `Waiting for battle` in the lobby and live data in battle.
   Ghost Drop markers are a separate full-screen layer and never receive touch.
5. The panel starts at 62% size, centered above the enemy King Tower. Drag the
   `⠿` area in its header to move it, drag the lower-right `↘` handle to resize
   it, `A` cycles opacity, and `X` closes it. Position and scale are saved.

Opponent identity is account-based (see above). The hand HUD, the recent-play
row and Ghost Drop all render from that one resolved identity, so a seat swap
cannot invert them independently. A confirmed account and the local deck are
cached and reused, and the footer names the identity in use: grey when proved,
amber while it is only the provisional seat.

The panel is `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` and sized to its
content, so it only steals touches that land on itself.

## Validation status

The v1 overlay infrastructure was verified on a Redmi K70 Pro (arm64-v8a,
Android 16 / SDK 36) with `tools/mock_probe.py`. The v2 tablet build adds the
shared card assets, native cycle and Ghost protocol.

The affordability gauge was verified **on the target tablet** (Xiaomi Pad 8 Pro,
`EXAMPLE-SERIAL-01`, Android 16 / SDK 36, arm64-v8a, 2136x3200 @ 440 dpi, build
`2.5-tablet` / versionCode 7) against `tools/mock_probe.py`: with the mock probe's
elixir sawtooth, tiles whose cost exceeded the current elixir rendered grey with
the colour wedge sweeping clockwise from 12 o'clock, low-cost tiles stayed fully
coloured, a higher-cost tile kept a thin grey sliver near the top as it approached
its cost, and every tile went fully coloured once the elixir passed its cost. The
`NEXT` row stayed unmarked throughout. Evidence: `../shots/tablet-hud-sweep.png`
(workspace `shots/`, outside this repository).

The mock probe was reached by pointing the app at a second port
(`probe_port=26889` in the app's preferences, via `adb reverse`) because the
game's own probe already owns `127.0.0.1:26888`; the preference was restored
afterwards, so the shipped default endpoint is unchanged. The round that added
the sliders reached it on `26888` itself, with the game closed and the mapping
removed again afterwards.

The two-layer gauge and the Ghost Drop opacity sliders were verified **on the
same tablet** in the round that introduced them, on real device pixels rather
than by eye: `adb shell screencap` of the live overlay, read back by
`tools/tablet_hud_visual_check.py`. The mock probe pinned the hand to
HogRider(4) / Prince(5) / P.E.K.K.A(7) / Skeletons(1), so one frame answers the
whole question. Evidence: `cmd_audit/ui-ux/` (`visual-check.txt` plus the six
captures).

| measurement (elixir 2, same hand) | sector the geometry covers | sector measured |
| --- | --- | --- |
| 4-cost tile | 0.464 | 0.475 |
| 5-cost tile | 0.492 | 0.498 |
| 7-cost tile | 0.590 | 0.607 |
| 1-cost tile (affordable) | 0.000 | 0.007, and 78% of its face still coloured |

At 4 elixir the same hand measures 5-cost 0.239 and 7-cost 0.461 while the
4-cost tile has no sector left and 91% of its face back in colour - the boundary
travels with the elixir and lands exactly on zero at the cost. The three
unaffordable tiles' faces measure a mean channel spread of 0.7-1.1, i.e. genuinely
desaturated, while the affordable tile measures 34-54.

The Ghost Drop slider was moved 0% -> 50% -> 100% and the marker's icon area
measured against its own 0% (backdrop) and 100% (art) frames: median alpha
0.000 / 0.507 / 1.000 on both pending markers, with the settings screen's
`ghost_card_opacity` reading back 0.0 / 0.5 / 0.65 (default restored) from the
preferences. The markers were already on the arena when the slider moved, which
is what the "no restart" path is for.

The panel's own JVM tests and APK signature/package checks pass. What the mock
cannot show is the *rate* of the sweep against genuinely regenerating elixir: the
arithmetic and the animation window are unit-tested, but the mock's elixir is
pinned or sawtoothed rather than played. A real match on the tablet was used for
that: two battles, both read the opponent's hand, elixir and Ghost Drop correctly.

| check | result |
| --- | --- |
| APK installs and launches | pass |
| `TYPE_APPLICATION_OVERLAY` window registered under `SYSTEM_ALERT_WINDOW` | pass (`ty=APPLICATION_OVERLAY fmt=TRANSLUCENT`) |
| connects to `127.0.0.1:26888` from the device | pass |
| parses `nulls-live.v3`, shows opponent elixir + 4 hand slots | pass |
| lobby frame `{"in_battle":false}` | shows `Probe: Connected` + `Waiting for battle`, no invented data |
| probe killed | flips to `Probe: Disconnected`, elixir/hand reset to `--`, process alive, no `FATAL EXCEPTION` |
| probe restarted | panel recovers to live data on its own |
| two-layer gauge on the target tablet (mock probe, 2 elixir) | pass: 4/5/7-cost tiles measure 0.475 / 0.498 / 0.607 of their face dark, the affordable tile 0.007 with full colour |
| gauge at the threshold (mock probe, 4 elixir) | pass: the 4-cost tile has no sector and full colour, the dearer tiles keep a smaller sector than at 2 elixir |
| Ghost Drop opacity 0 / 50 / 100% | pass: measured alpha 0.000 / 0.507 / 1.000, live on markers already on the arena |
| Ghost Drop opacity survives a restart | pass: `ghost_card_opacity` reads back from the preferences; a fresh app start shows `当前 100%` on the slider |
| two real matches on the tablet | pass: opponent hand, elixir and Ghost Drop all correct in play |

The identity rules were replayed over the recorded live captures
(`cmd_audit/identity_replay.py`, evidence in `cmd_audit/identity-fix-report.md`):
every battle segment where the instrumented account was present resolved to that
account, both with the GHOST stream and with it dropped, and no segment ever
confirmed the other account. With both device-local signals available the lock
lands on the **first** card play (battle tick 194–272); with Ghost Drop off it
takes three plays. One segment put the local player at seat 1 and another at
seat 0 in the same session, which is the swap that a seat rule cannot survive.

`adb reverse` was removed after the run, so the phone forwards nothing to the PC.

The probe install itself was **not** performed on this device: it reports
`ro.boot.flash.locked=1`, `ro.boot.verifiedbootstate=green`, SELinux enforcing,
and exposes no `su`/`magisk`/`ksud`/`apd` binary, so it is not rooted and the
game library cannot be replaced. `tools/install_probe.py` is unchanged and will
run as-is once a rooted device is available.

## Why there is no root-free install path for this game

The obvious no-root idea is to bake the probe into the game APK (replace
`lib/arm64-v8a/libscid_sdk.so`, ship the original as `libscid_sdk_real.so`) and
install that build, since `android:extractNativeLibs="true"` means the libraries
are extracted to a real directory and the probe's `dlopen("libscid_sdk_real.so")`
resolves normally. `tools/repack_apk_probe.py` implements exactly that and
produces a valid signed APK. **It does not work for Null's Royale.**

Null's Royale validates the client with a component of its own:
`xyz.daniillnull.WildCat` raises the error code `The1001`, and its constructor
path references `http://wildcat-api.dnull.xyz/check`. A re-signed build is
rejected at startup with

```
Null's Royale failed to start due to unexpected error (The1001). Try again later.
```

Control experiment that pins this on the signature and not on the probe:

| build installed | result |
| --- | --- |
| pristine original, original signature | starts normally |
| pristine original, **re-signed, zero content changes** | `The1001`, black screen |
| probe baked in, re-signed | `The1001`, black screen |

So the probe is never even reached. Patching the check is not a shortcut either:
`com.supercell.titan.GameApp.isSignatureValid()` already compiles to
`const/4 v0, #0; return v0` (always false) in the *working* original, and
`libwildcat.so` carries a Java-method hook framework — the dex stub is not the
gate. The verification is Null's own, and it is server-backed.

Conclusion: on a non-rooted device the probe cannot be injected. With root,
`tools/install_probe.py` replaces the extracted library in place, the APK
signature stays untouched, `WildCat` is satisfied, and everything above applies
unchanged.

## Mock harness (development only)

`tools/mock_probe.py` speaks the same one-command-per-connection protocol as the
probe, with a changing tick, a sawtooth elixir value and a rotating hand:

```bash
python tools/mock_probe.py            # battle frames
python tools/mock_probe.py --idle     # {"in_battle":false}
adb reverse tcp:26888 tcp:26888       # device 127.0.0.1:26888 -> this host
adb reverse --remove tcp:26888        # always remove it afterwards
```

The default frame moves, which is what the live check wants and what a
screenshot does not. `--elixir` and `--hand` pin the opponent's side, `--ghost`
answers the `GHOST` command with pending enemy PlayCard events, and
`--self-account` sets which account the client should recognise as its own:

```bash
python tools/mock_probe.py --no-arm-gate --elixir 2 \
    --hand 26000021,26000016,26000004,26000010 \
    --ghost 26000004,9000,8000 --ghost 26000016,4000,11000 \
    --self-account 900000001
```

`tools/check_overlay_frame.py` cross-checks one frame against the existing
`bridge/probe_client.py` parser so the Android and PC clients cannot drift.

## Failure behaviour

- Probe absent / game closed: `Probe: Disconnected`, no stale numbers.
- Probe reappears (game restart): reconnect is automatic; each poll is a short
  socket, failures back off 200 ms -> 2 s.
- Malformed or partial JSON: unknown fields degrade to `--` / `Unknown(<id>)`;
  the client never throws into the UI.

## On-device root installer (`:installer`)

When the device (or a rooted VM guest) already has root, `tools/install_probe.py`
is not needed: the `:installer` module is the same operation driven from inside
Android. It builds to `installer/build/outputs/apk/debug/installer-debug.apk`,
carries the compiled probe in its assets (staged at build time from
`probe/artifacts/candidates/stable-candidate/libscid_sdk.so`), and requests **no
permissions at all** — every privileged step goes through `su`.

| button | effect |
| --- | --- |
| Preflight | read-only: proves `uid=0`, finds the installed game, verifies the `libg.so` fingerprint and that the installed SDK is a clean original |
| Install | backs the original SDK up to app storage, writes the probe over `libscid_sdk.so` **in place** (inode, owner, mode and SELinux label survive), places the original as `libscid_sdk_real.so`, then re-verifies both by SHA-256 |
| Restore | writes the saved original back and removes `libscid_sdk_real.so` |
| Receipt | shows the recorded backup metadata |

It fails closed: any fingerprint or symbol mismatch aborts before a byte is
written, and the APK signature is never touched, so the client's own validation
(`WildCat`) stays satisfied.

## Battle History (对战记录)

A second screen, reached from the settings column (`对战记录 / Battle History`),
plus a recorder service that runs independently of the overlay. The persistent
history is now a projection of game-owned Battle Log data, not a diary of probe
state transitions.

```text
live probe -> BattleRecorder -> bounded process-local working sessions ----+
                                                                         |
game Battle Log replay payload -> NullsReplayParser/NullsHistoryImporter  |
                                      -> BattleHistoryReconciler <--------+
                                      -> finalized SQLite rows only
                                      -> HistoryActivity + CSV/JSON export
```

**Why it is a separate service.** The HUD can be off, on, restarted or
reconfigured without the recorder losing its place, and the recorder's poll loop
can never slow the HUD's frame path. Nothing in `OverlayService` or the renderer
changed; the only edit outside the new package is one additive field on
`ProbeEvent.Snapshot` (the raw response line, ignored by the HUD) and the two
entry buttons in the settings column.

### Authority and reconciliation

| data | authority |
| --- | --- |
| battle/replay id, both player ids, both eight-card decks, crowns and result | game Battle Log/replay payload |
| start/end ticks and `full_battle` | optional live working session, after a conservative match |
| display names and card metadata | parsed payload plus the local card catalogue |

Live capture never decides `WIN`, `LOSS` or `DRAW`, and never writes a formal
row. Its result is always `INCOMPLETE`. Consequently loading screens, idle
snapshots, scene exits, service restarts and a 0-0 live snapshot cannot become a
fake draw.

The reconciler rejects undecided results, missing/equal player ids, malformed
decks, and non-history sources. Identity priority is official battle id, then
replay id (`rndSeed` for the captured replay format), then a composite hash of
both players, both decks, result/mode/arena and an approximate authoritative
time bucket. A file modification time is never used as identity. Authority wins
all disputed identity/deck/crown/result fields; matching live telemetry may add
timing only.

The service checks unchanged payload inputs at low frequency (30 seconds) and
only while no battle is active. `HistoryActivity` can also request the same
sync. Both paths share one importer/reconciler and canonical upsert behavior.

The current acquisition boundary is explicit: a captured Battle Log replay
payload must be present as `history_payload*.json` in the app export directory.
The installed probe does not yet extract every Battle Log entry automatically;
opening a Battle Log replay is the proven point where the authoritative payload
exists. This limitation must not be papered over by promoting live inference.

### Interruption and crash behavior

There is at most one active live working session and eight recently completed
ones, all in process memory. Leaving early, killing the overlay, or killing the
process discards that telemetry and writes nothing. After restart, an
authoritative Battle Log payload creates or updates the canonical row. Replaying
or re-importing the same payload is idempotent.

### Schema

`clashtracker_history.db`, version 1, `battles` + `battle_cards` + `meta`.
Upgrades are append-only steps in `BattleDb.MIGRATIONS`; `onDowngrade` never drops
history. Existing live/incomplete or malformed rows are left untouched for
forensics but excluded from official queries and exports. A later valid history
payload is upserted under its canonical key; no migration bulk-deletes user data.

### Export

Only finalized game-history rows are visible and exportable. `导出 CSV` /
`导出 JSON` write to **both** the app's external directory
(`Android/data/dev.clashaiaa.overlay/files/exports/`) and, on API 29+, the public
`Download/ClashTracker/` collection via MediaStore. The CSV is UTF-8 **with a
BOM** so Excel on a Chinese Windows install does not read it as GBK.

### Validation

175 JVM unit tests pass (`:app:testDebugUnitTest`), including malformed-history
rejection, source/status gating, key priority, fallback-key stability, live
timing merge, the no-live-finalization rule, exports, statistics and the recorder
state machine. `research/battlelog/FINDINGS.md` records the acquisition evidence
and remaining automatic-extraction gap.

## Deliberately out of scope

No visual recognition, OCR, elixir estimation, inferred enemy cards, auto-play,
touch injection, anti-cheat bypass, telemetry or analytics. Both overlay layers
are display-only; Ghost Drop consumes the probe's pending-command feed and does
not submit a game command.
