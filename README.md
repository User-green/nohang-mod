# nohang-mod

A NeoForge server-side mod that force-exits a Minecraft server whose JVM hangs
after the world save has completed.

**Start with [SPEC.md](SPEC.md)** — it contains the diagnosis, the measured
timings, the design, and the acceptance criteria.

Read §10 first: this is a workaround for a bug in another mod
(`fucksable-1.6.8`), and it may not need to exist. Confirm that before building.

`evidence/` holds the two JVM thread dumps the diagnosis is based on.
