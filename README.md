# DreamBot — Fabric mod for Minecraft 1.21.11

## Easy way to get a .jar (no JDK install needed)

1. Make a free GitHub account.
2. Create a new repo. Upload this entire project folder (drag-drop in
   the GitHub web UI works fine — keep folder structure intact).
3. Click the **Actions** tab. The "Build DreamBot" workflow runs
   automatically — wait ~5 minutes for the green check.
4. Click into the run, scroll to **Artifacts**, download `dreambot-jar.zip`.
   Inside is your `.jar`. Drop it in `.minecraft/mods/`.
5. For a permanent download URL: tag a commit `v1.0.0` and the workflow
   creates a GitHub Release with the jar attached.

## Build locally instead

JDK 21 + `./gradlew build`. Jar lands in `build/libs/`.

## Commands

**Ore finder**
- `/findore <ore> [radius]` — finds + marks nearest. Default mode only
  considers ores with at least one air-touching face (the ones you'd
  see while exploring caves anyway).
- `/listores`, `/clearmark`

**Structures & analysis**
- `/findstronghold`, `/findstructure <id>`
- `/waypoints`, `/waypoints mark <n>`, `/waypoints clear`
- `/spawninfo`, `/seedpicker <profile> [count]`

**Inventory**
- `/sortinv` — sorts by item name
- `/stackinv` — consolidates partial stacks of same item

**Terrain**
- `/pos1`, `/pos2`, `/terrain styles`, `/terrain suggest`,
  `/terrain <style>`, `/terrain cancel`

**Auto-Mine** (botting — singleplayer/LAN only)
- `/automine [ore]` — mines selected ore type within 4-block reach,
  auto-eats food below 17 hunger, stops on death/low health.
- `/stripmine [length]` — serpentine strip miner. Walks forward mining
  any block in path. After [length] blocks (default 16) turns left,
  walks 2 over, turns left again, mines the parallel tunnel back.
  Auto-eats and stops on death like /automine. Stops on bedrock or if
  it gets stuck for 3+ seconds.

**Fast place**: `/fastplace`

**More QoL**
- `/fullbright` — toggles gamma to 5.0 (vanilla cap is 1.0)
- `/back` — marks your last death point with the compass arrow
- `/autosprint` — toggle, auto-sprints when walking forward (skips if hungry)
- `/autotool` — toggle, swaps to the best hotbar tool when attacking a block
- `/sethome` — saves current position, `/home` marks it with compass arrow
- `/alias set <name> <text>` — typing `.name` in chat sends `<text>`
- `/alias list`, `/alias del <name>`
- `/ignore <player>`, `/unignore <player>`, `/ignore list` — hides that
  player's chat messages from your screen
- **C key** (rebindable in Controls → DreamBot) — hold to zoom (4x)
- **Durability warning** — chat alert when held tool drops below 10%,
  HUD line when below 25%
- **Armor HUD** — lists any armor piece below 25% durability
- **Hand info line** — compact info about held item
- **Real clock** — system time HUD line
- **Session timer** — how long you've been in the current world
- **CPS counter** — left-click rate for PvP
- **XP details** — level, current XP, total XP (vanilla only shows level)
- **Saturation** — numeric food + saturation values
- **Elytra HUD** — auto-shows while gliding: speed in blocks/sec,
  altitude, altitude above ground, facing
- **Keys overlay** — WASD + space bar visual keyboard
- **Death point auto-saved** on death; use `/back` to mark it
- **FPS / ping / light level** lines available in HUD config


**Speedrun assist**
- `/speedrun start` / `stop` / `reset`
- `/speedrun split <n>` - manual split
- `/speedrun splits` - list splits in current run
- `/speedrun pb` - show personal best
- `/speedrun savepb` - save current splits as PB reference
- HUD: live timer, last split with +/- delta vs PB, item counters
  (Rods/Pearls/Eyes), dimension readiness check
- Auto-splits on dimension change with live delta comparison
- Readiness line per dimension:
  - Overworld: pick / portal / sword check
  - Nether: rod + pearl progress toward 12 eyes
  - End: water / bed / pearls for dragon fight
- PB and PB splits persist in config

**SR tools**
- `/srreset` - kill + clear inventory (singleplayer only)
- `/netherof <x> <z>` - overworld to nether coords (divide by 8)
- `/overworldof <x> <z>` - nether to overworld coords (multiply by 8)
- `/blind eye1` / `eye2` / `calc` / `clear` - stronghold triangulation.
  Run right after each eye throw while still facing the direction you
  threw it; calc intersects the two lines and marks the stronghold.

**Webhook** (Discord-compatible)
- `/webhook set <url>` (don't share your URL in public chats — it's a secret)
- `/webhook clear`, `/webhook test`
- `/webhook toggle <event>` — totem_pop, warden_warning, ore_found, automine_done

**20-pack QoL**
- `/copycoords` — copies XYZ to system clipboard
- `/sharecoords` — sends your coords to server chat
- `/clearchat` — wipes chat buffer
- `/autorespawn` — toggle, skips death screen
- `/permachat` — toggle, prevents chat auto-fade (flag only; full
  perma-chat requires a mixin not yet added)
- `/togglesneak` — toggle, sneak key becomes press-once instead of hold
- `/stats` — show session kill/walk/damage counters
- `/resetstats` — reset session counters
- **Crosshair info** — HUD line showing block/entity under cursor + dist
- **Spawn safety** — "UNSAFE: mobs can spawn here" when light=0 + sky<8
- **Potion timers** — HUD line listing active effects with countdown
- **Breath HUD** — numeric air while underwater, color-coded
- **Freeze HUD** — freeze ticks in powder snow
- **Sleep info** — time until sleepable / morning countdown
- **Kill counter, walk distance, damage taken** — session stats HUD lines
- **Bed auto-marker** — sleeping in a bed marks its position
- **Fall damage predictor** — shows expected damage while falling
- **Nearby entity counter** — hostile/passive/players within 32 blocks
- **Low breath warning** — chat alert when air drops below 60

**20-pack v2 (more QoL)**
- `/wp save <n>` / `/wp list` / `/wp mark <n>` / `/wp del <n>` — named persistent waypoints
- `/finditem <name>` — search inventory + tells you the slot
- `/repeat <n> <cmd>` — runs a command N times
- `/timer <secs> <msg>` — countdown with chat alert + bell sound
- `/day` — show current Minecraft day
- `/mobs` — list nearby mobs grouped by type
- `/motd` — show current server's MOTD
- `/note add <text>` / `/note list` / `/note clear` — session notes
- `/autogreet <msg>` / `/autogreetoff` — sends a canned message 3s after joining a server
- **Spawn distance HUD** — distance + direction to world spawn
- **Velocity HUD** — your X/Y/Z movement speed in blocks/sec
- **Last damage HUD** — shows what last hurt you (fall, fire, mob name)
- **Pearl cooldown HUD** — right-click cooldown after throwing an ender pearl
- **Reach HUD** — your current attack/place reach
- **Weather HUD** — clear/rain/thunder
- **TPS estimator HUD** — measures real client tick rate
- **Countdown timer HUD** — appears while a /timer is active
- **Name highlight** — chat messages mentioning your username turn yellow + ping sound

**UI**
- `/dreambot` or **Right Shift** — main menu
- `/hudconfig` — HUD layout

## HUD

Top-left by default. Lines: totems, marker, coords, day/time, biome,
warden, fastplace. Each toggleable. Anchor to any corner. Scale 0.75-2x.
Optional chat timestamps.

## Auto-mine honest disclosure

Sends real dig packets with rotation. Functions on vanilla servers but
this is botting — essentially every multiplayer server bans unattended
automation. Use in singleplayer or your own LAN. If you use it on public
servers, you will get banned, and that's on you.

## Making a webhook

Discord: Channel settings → Integrations → Webhooks → New Webhook →
Copy URL → `/webhook set <url>` in chat.
