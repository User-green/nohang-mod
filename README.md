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
| `delaySeconds`            | int  | `15`    | Seconds after shutdown-start before halting                   |
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

The invariant for `delaySeconds` (see SPEC §5) is:

```
save duration  <<  delaySeconds  <<  TimeoutStopSec
```

On `hoyrent`, save time is ~3.4s and the unit's `TimeoutStopSec` is 30s, so
the default of 15s gives roughly 4× margin over the save while halting well
before systemd's SIGKILL.

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
     `NoHang config loaded (nohang-server.toml): enabled=true, delaySeconds=15, ...`
   - on stop: `ServerStoppingEvent fired; arming shutdown watchdog ...`

## Acceptance

Run the checks in SPEC §8 against the real server before declaring this done.
In particular, **#6 — repeat five times**. The bug only triggers on actual
shutdown, and saves can be sensitive to races.

---

## ⚠️ SPEC §6 — simplebackups compatibility (read before shipping)

The spec requires the implementer to establish, **on the real server**, that no
shutdown-time work (notably `simplebackups`) gets truncated by a 15-second halt.

`halt()` skips every remaining shutdown hook, including any backup the server
starts during stop. A watchdog firing mid-backup trades a slow stop for a
**corrupt archive**, which is a strictly worse outcome.

Steps:

1. Trigger one stop with `simplebackups` enabled.
2. Inspect the resulting backup archive — does it open, does it have the right
   number of files?
3. If unsure, raise `delaySeconds` (e.g. 30–45) and re-test. The spec's
   guidance: *"when uncertain, bias the delay upward."*

Until this is verified on `aserver`, treat the default `delaySeconds = 15` as
provisional.

---

## License

GNU General Public License v3.0 or later — full text in [LICENSE.md](LICENSE.md),
per-file headers in the sources.
