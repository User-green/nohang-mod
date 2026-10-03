# NoHang

A **server-only** NeoForge mod that force-exits a dedicated Minecraft server whose JVM hangs
during shutdown, *after* the world has been saved.

Some modpacks never finish shutting down: the world save completes, then a mod blocks the
server thread forever and the process has to be killed. Under systemd (or any other
supervisor) that means every stop runs into the stop timeout, ends in `SIGKILL`, and leaves
the service marked as failed. NoHang waits for vanilla's world save to be confirmed durable,
gives other mods a short grace period, and then halts the JVM itself, so the server stops in
seconds and exits cleanly.

It does not fix the mod that hangs. It bounds the damage.

- Minecraft **1.21.1**, NeoForge **21.1.235+**
- Dedicated servers only. Clients do not need it, and on a client the mod is never loaded
  (`@Mod(dist = Dist.DEDICATED_SERVER)`; all dependencies are declared `side="SERVER"`).

## Install

Download `nohang-<version>.jar` from the [Releases](../../releases) page and put it in the
server's `mods/` directory. No configuration is required.

On the next start, `logs/latest.log` should contain:

```
NoHang config loaded (nohang-server.toml): enabled=true, delaySeconds=15, postSaveDelaySeconds=5, ...
```

On every stop:

```
Shutdown watchdog armed: halt 5s after the world save is durable, or 15s from now at the latest ...
Vanilla world save confirmed durable (saveAllChunks returned, level.dat written); halting in 5s ...
```

If the JVM exits on its own within the grace period, nothing else happens. If it does not,
NoHang logs a WARN, dumps all thread stacks (so you can find the culprit), and calls
`Runtime.halt()`.

## How it decides when to halt

Two deadlines run at once; whichever comes first wins.

**After the save: the normal trigger.** Vanilla's `MinecraftServer.stopServer()` posts
`LevelEvent.Unload` right after `saveAllChunks()` returns (every level's chunks written,
`level.dat` written last) and right before `ServerLevel.close()`, which is where the hangs
this mod was written for occur. The first such event of a shutdown is a precise "all vanilla
world data is on disk" signal. NoHang halts `postSaveDelaySeconds` (default 5) after it.

**Absolute bound: the backstop.** `delaySeconds` (default 15) after shutdown starts,
regardless. This only matters if a hang ever sits *before* the save. In that case the log
says so explicitly, because the halt may then be interrupting a save rather than following
one.

The same `LevelEvent.Unload` listener also arms the watchdog on **crash-path shutdowns**,
where the server thread dies of an exception, `ServerStoppingEvent` never fires, but
`stopServer()` still runs and can hang in the same way.

## Configuration

`config/nohang-server.toml`, created on first start:

| Key                     | Type | Default | Notes                                                        |
| ----------------------- | ---- | ------- | ------------------------------------------------------------ |
| `enabled`               | bool | `true`  | Kill switch; `false` restores stock behaviour                |
| `delaySeconds`          | int  | `15`    | Absolute bound: seconds after shutdown starts before halting |
| `postSaveDelaySeconds`  | int  | `5`     | Normal trigger: seconds after the world save is durable      |
| `exitCode`              | int  | `0`     | Exit code passed to `Runtime.halt()`                         |
| `dumpThreadsBeforeHalt` | bool | `true`  | Dump all thread stacks to the log before halting             |

NeoForge also honours a per-world override at `world/serverconfig/nohang-server.toml`, but
only if you create that file yourself. Edit the one in `config/` unless you want a
per-world setting.

`postSaveDelaySeconds` is the grace period for other mods' shutdown work (their own unload
listeners, backups, and so on). Raise it if that work needs longer: halting cuts it off.

`delaySeconds` should satisfy:

```
world save duration  <<  delaySeconds  <<  your supervisor's stop timeout
```

Values below 10 or above 25 log a WARN at load time. If `delaySeconds` exceeds the stop
timeout, the supervisor kills the server first and the backstop never fires.

### systemd note

When systemd stops a Java service, the JVM is usually terminated by `SIGTERM`, and Java then
exits with status **143** even when shutdown went perfectly. If your unit reports
`Result=exit-code` after a clean stop, add this to the `[Service]` section:

```ini
SuccessExitStatus=143
```

## Build

Requires JDK 21.

```sh
./gradlew build
```

The jar is written to `build/libs/`. Pushing a tag `v<mod_version>` (for example `v1.0.0`)
builds the jar on GitHub Actions and attaches it to a GitHub release; the tag must match
`mod_version` in `gradle.properties`.

## Background

NoHang was written for one specific modpack server whose JVM hung on every stop. The
diagnosis (thread dumps, measured save timings), the design, and the acceptance criteria are
in [SPEC.md](SPEC.md); the raw thread dumps are in [`evidence/`](evidence/). In that pack the
hang came from a mixin into Sable's chunk-map close path (`awaitBeforeClose`, joining a
`CompletableFuture` that never completes), but NoHang does not depend on Sable or on that
particular bug.

Tested on that server (NeoForge 21.1.235, Java 25 runtime) with five consecutive
stop/start cycles: every stop finished in about 10 to 12 seconds, the world save was
confirmed before every halt, and the server restarted cleanly each time.

## Use of AI

This project was developed with substantial help from a large language model: Anthropic's
Claude, used through Claude Code. The model wrote most of the code, the specification and
this documentation, under the direction of the maintainer, who reviewed the changes and ran
the tests on a real server. Commits made with its help carry a `Co-Authored-By: Claude`
trailer.

## License

Copyright (C) 2026 UserGreen

GNU Affero General Public License v3.0 or later. Full text in [LICENSE.md](LICENSE.md).
