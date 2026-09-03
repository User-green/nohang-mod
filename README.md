# nohang-mod

A NeoForge server-side mod that force-exits a Minecraft server whose JVM hangs
after the world save has completed.

**Start with [SPEC.md](SPEC.md)** — it contains the diagnosis, the measured
timings, the design, and the acceptance criteria.

Read §10 first: this is a workaround for a bug in another mod
(`fucksable-1.6.8`), and it may not need to exist. Confirm that before building.

`evidence/` holds the two JVM thread dumps the diagnosis is based on.

---

## Build

Requirements:

- JDK 21 (`java -version` should report 21.x)
- Network access for the first build (Gradle, NeoForge, Minecraft artifacts)

```sh
./gradlew build
```

The output JAR is at `build/libs/nohang-1.0.0.jar`. Drop it into the server's
`mods/` directory alongside the rest of the pack.

No configuration is required to use the defaults; the mod writes its config to
`config/nohang-server.toml` on first load (see Configuration below).

## How it fires

Two deadlines run at once; whichever comes first wins.

**Save-completion deadline — the normal trigger.** Vanilla's `MinecraftServer.stopServer()`
posts `LevelEvent.Unload` directly after `saveAllChunks()` has returned (every level's chunks
written, `level.dat` written last) and directly before `ServerLevel.close()` — the call that
wedges. The first such event of a shutdown is therefore a precise "all vanilla world data is
durable" signal, and the last one available ahead of the hang. The watchdog halts
`postSaveDelaySeconds` (default 5) after it.

**Absolute bound — the backstop.** `delaySeconds` (default 15) from the start of shutdown,
regardless. This only takes effect if the save is never confirmed, i.e. if a future hang sits
*ahead* of the save rather than after it. In that case the log says so explicitly, because
the halt may then be interrupting a save rather than following one.

The `LevelEvent.Unload` listener also arms the watchdog if it is not already armed, which
covers **crash-path shutdowns**: when the server thread dies of an exception,
`MinecraftServer.runServer` skips `handleServerStopping` — so `ServerStoppingEvent` never
fires — but still reaches `stopServer()` through its `finally` block, where it can wedge
exactly as on a clean stop. Before this, a crash left the JVM to the systemd SIGKILL.

## Scope

The watchdog arms **only on a dedicated server**. `ServerStoppingEvent` also fires
for the integrated (single-player) server every time a player leaves a world; halting
there would kill the game client, possibly while another world was already loading.
On a client the mod loads and does nothing.

## Configuration

Server-side TOML at `config/nohang-server.toml`:

| Key                       | Type | Default | Notes                                                         |
| ------------------------- | ---- | ------- | ------------------------------------------------------------- |
| `enabled`                 | bool | `true`  | Kill switch; `false` restores stock behaviour                 |
| `delaySeconds`            | int  | `15`    | Absolute bound: seconds after shutdown-start before halting   |
| `postSaveDelaySeconds`    | int  | `5`     | Normal trigger: seconds after the world save is durable        |
| `exitCode`                | int  | `0`     | Exit code passed to `Runtime.halt()`                          |
| `dumpThreadsBeforeHalt`   | bool | `true`  | Dump all stacks to `logs/latest.log` before halting           |

### Where the file actually lives

NeoForge loads `SERVER`-type configs with
`ConfigTracker.loadConfigs(Type.SERVER, FMLPaths.CONFIGDIR, <world>/serverconfig)`.
The second path is an **override**, consulted only if a file of that name already
exists there. So:

- Default, and the file the mod creates on first start: `config/nohang-server.toml`
- Per-world override, only if you create it by hand: `world/serverconfig/nohang-server.toml`

Edit the one in `config/` unless you deliberately want a per-world setting.

`postSaveDelaySeconds` is a grace period for other mods' shutdown work — they run their own
`LevelEvent.Unload` listeners and `ServerLevel.close()` paths after the vanilla save. Raise it
if such work needs longer; halting mid-flight cuts it off.

The invariant for `delaySeconds` (see SPEC §5) is:

```
save duration  <<  delaySeconds  <<  TimeoutStopSec
```

On `hoyrent`, save time is ~3.4s and the unit's `TimeoutStopSec` is 30s, so
the default of 15s gives roughly 4× margin over the save while halting well
before systemd's SIGKILL. Values below 10 or above 25 log a WARN at load time —
above the unit's timeout the mod is a silent no-op, because systemd SIGKILLs first.

With the save-completion trigger in play the expected stop is roughly
save + `postSaveDelaySeconds` ≈ 3.4s + 5s, and `delaySeconds` should rarely be reached.

## Multi-version support (planned)

Current target: **Minecraft 1.21.1 / NeoForge 21.1.235**.

Future targets (not yet built): 1.20.1 (NeoForge), 1.16.5 (Forge),
1.12.2 (Forge). Each older version requires its own build because the
event-bus API and Gradle tooling differ; the mod code itself is small and
should port straightforwardly.

## Deploy

1. Copy `build/libs/nohang-1.0.0.jar` into `/home/servers/minecraft/hoyrent/mods/`
2. Restart the server.
3. Verify in `logs/latest.log`:
   - on server start (when the SERVER config is loaded):
     `NoHang config loaded (nohang-server.toml): enabled=true, delaySeconds=15, postSaveDelaySeconds=5, ...`
   - on stop: `ServerStoppingEvent fired; arming shutdown watchdog ...`
   - then: `Vanilla world save confirmed durable (saveAllChunks returned, level.dat written)`
     — if this line is missing, the halt fell back to the absolute bound and the log will
     say so; treat that as a signal that the hang has moved.

## Acceptance

Run the checks in SPEC §8 against the real server before declaring this done.
In particular, **#6 — repeat five times**. The bug only triggers on actual
shutdown, and saves can be sensitive to races.

---

## SPEC §6 — simplebackups: settled, do not raise again

**Closed by operator decision, 2026-09-03.** `simplebackups` shutdown-time interaction is
not a blocker for this mod and is not to be re-raised in reviews, summaries, or future
iterations. Treat it as answered.

The mechanics, for the record only: `halt()` skips every remaining shutdown hook, and
`postSaveDelaySeconds` is the knob that governs how much room shutdown-time work gets after
the vanilla save. If a backup ever does turn up truncated, raise that value — that is the
whole remedy, and it needs no further investigation before shipping.

---

## License

GNU General Public License v3.0 or later — full text in [LICENSE.md](LICENSE.md),
per-file headers in the sources.
