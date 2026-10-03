/*
 * NoHang — shutdown watchdog for a modded Minecraft server.
 * Copyright (C) 2026 UserGreen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.nohang;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class NoHangConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("""
                    Master switch for the shutdown watchdog.
                    When false, this mod is a no-op and the JVM relies entirely on the external
                    supervisor (systemd TimeoutStopSec) to terminate a hung shutdown.""")
            .define("enabled", true);

    public static final ModConfigSpec.IntValue DELAY_SECONDS = BUILDER
            .comment("""
                    Absolute upper bound, in seconds, from the start of shutdown to the forced halt.

                    This is the outer backstop, not the normal trigger. In the normal case the
                    watchdog halts postSaveDelaySeconds after the vanilla world save is confirmed
                    durable (see below), which is much sooner. This bound only takes effect when
                    that confirmation never arrives — e.g. if a future wedge moves ahead of the
                    save instead of after it.

                    Must satisfy:  save duration  <<  delaySeconds  <<  TimeoutStopSec

                    Observed save time on hoyrent is ~3.4s and TimeoutStopSec is 30s, so the
                    default 15s gives ~4x margin over the save while still halting well before
                    systemd's SIGKILL. Values below 10 or above 25 trigger a WARN at load time.""")
            .defineInRange("delaySeconds", 15, 1, 600);

    public static final ModConfigSpec.IntValue POST_SAVE_DELAY_SECONDS = BUILDER
            .comment("""
                    Seconds to wait after the vanilla world save is confirmed durable before
                    halting. This is the normal trigger.

                    "Durable" means MinecraftServer.saveAllChunks() has returned: every level's
                    chunks are written and level.dat (written last) is on disk. The watchdog
                    learns this from the first LevelEvent.Unload of the shutdown, which vanilla
                    posts immediately after that call and immediately before ServerLevel.close()
                    -- the call that actually wedges.

                    The grace period exists because other mods do shutdown work after the vanilla
                    save, in their own LevelEvent.Unload listeners and in ServerLevel.close().
                    Raise it if such work needs longer; halting mid-flight cuts it off.""")
            .defineInRange("postSaveDelaySeconds", 5, 0, 600);

    public static final ModConfigSpec.IntValue EXIT_CODE = BUILDER
            .comment("""
                    Exit code passed to Runtime.halt() when the watchdog fires.
                    0 so systemd records Result=success / ActiveState=inactive (not failed).""")
            .defineInRange("exitCode", 0, 0, 255);

    public static final ModConfigSpec.BooleanValue DUMP_THREADS_BEFORE_HALT = BUILDER
            .comment("""
                    Log every thread's stack trace to logs/latest.log right before halting.
                    Keeps the underlying wedge (fucksable-1.6.8 mixin
                    SubLevelHoldingChunkMap.awaitBeforeClose) diagnosable rather than silently
                    masked by the watchdog.""")
            .define("dumpThreadsBeforeHalt", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private NoHangConfig() {}
}
