/*
 * NoHang — shutdown watchdog for a modded Minecraft server.
 * Copyright (C) 2026 nohang contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.nohang;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import org.slf4j.Logger;

@Mod(NoHangMod.MOD_ID)
public class NoHangMod {
    public static final String MOD_ID = "nohang";
    private static final Logger LOG = LogUtils.getLogger();

    public NoHangMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, NoHangConfig.SPEC, "nohang-server.toml");

        // SERVER configs are not loaded at mod-construction time — ConfigTracker only opens
        // STARTUP configs eagerly, the rest are opened in ServerLifecycleHooks
        // .handleServerAboutToStart. Reading a ModConfigSpec value here would throw
        // IllegalStateException("Cannot get config value before config is loaded.") and take
        // the whole server down at boot. So all config reads happen from the config events
        // below, or later still, at arming time.
        modEventBus.addListener(NoHangMod::onConfigLoading);
        modEventBus.addListener(NoHangMod::onConfigReloading);

        NeoForge.EVENT_BUS.addListener(NoHangMod::onServerStopping);
        NeoForge.EVENT_BUS.addListener(NoHangMod::onLevelUnload);
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        report(event.getConfig(), "loaded");
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        report(event.getConfig(), "reloaded");
    }

    private static void report(ModConfig config, String what) {
        if (config.getSpec() != NoHangConfig.SPEC) {
            return;
        }
        int delay = NoHangConfig.DELAY_SECONDS.get();
        if (delay < 10) {
            LOG.warn("delaySeconds={} is implausibly low (<10). "
                    + "World save time on hoyrent is ~3.4s; ensure delaySeconds exceeds it. "
                    + "See SPEC.md §5 'Choosing delaySeconds'.", delay);
        } else if (delay > 25) {
            // The bound is only useful if it fires before the supervisor gives up. Above the
            // unit's TimeoutStopSec the mod is a silent no-op: systemd SIGKILLs first and
            // nothing in the log says why.
            LOG.warn("delaySeconds={} is high. It must stay below the supervisor's stop timeout "
                    + "(TimeoutStopSec is 30s on hoyrent) or the watchdog never fires and systemd "
                    + "SIGKILLs as before. See SPEC.md §5 'Choosing delaySeconds'.", delay);
        }
        LOG.info("NoHang config {} ({}): enabled={}, delaySeconds={}, postSaveDelaySeconds={}, "
                        + "exitCode={}, dumpThreadsBeforeHalt={}",
                what,
                config.getFileName(),
                NoHangConfig.ENABLED.get(),
                delay,
                NoHangConfig.POST_SAVE_DELAY_SECONDS.get(),
                NoHangConfig.EXIT_CODE.get(),
                NoHangConfig.DUMP_THREADS_BEFORE_HALT.get());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        // ServerStoppingEvent also fires for the *integrated* server every time a player
        // leaves a single-player world. Halting the JVM there would kill the game client
        // outright — and if another world had already begun loading, mid-save. This mod is
        // only ever meant to act on a dedicated server.
        if (!event.getServer().isDedicatedServer()) {
            LOG.debug("Integrated server stopping; watchdog not armed (dedicated servers only)");
            return;
        }
        if (!NoHangConfig.ENABLED.get()) {
            LOG.info("nohang disabled (enabled=false); watchdog will not arm");
            return;
        }
        int delay = NoHangConfig.DELAY_SECONDS.get();
        int postSave = NoHangConfig.POST_SAVE_DELAY_SECONDS.get();
        int exitCode = NoHangConfig.EXIT_CODE.get();
        boolean dump = NoHangConfig.DUMP_THREADS_BEFORE_HALT.get();
        LOG.warn("ServerStoppingEvent fired; arming shutdown watchdog (postSaveDelay={}s, bound={}s, "
                + "exitCode={}, dumpThreads={}). Runtime.halt({}) will be called {}s after the world "
                + "save is durable, or {}s from now at the latest.",
                postSave, delay, exitCode, dump, exitCode, postSave, delay);
        ShutdownWatchdog.arm(delay, postSave, exitCode, dump);
    }

    /**
     * Vanilla posts {@code LevelEvent.Unload} in {@code MinecraftServer.stopServer} directly
     * after {@code saveAllChunks()} has returned and directly before {@code ServerLevel.close()},
     * the call that wedges. The first one of a shutdown is therefore a precise "all vanilla
     * chunks and level.dat are on disk" signal, and it is the last such signal ahead of the hang.
     *
     * <p>On a dedicated server this event has exactly one vanilla posting site, the one above.
     * The {@code isStopped()} gate keeps a dimension-lifecycle mod that unloads a level during
     * normal play from being mistaken for a shutdown: {@code MinecraftServer.stopped} is set
     * immediately before {@code stopServer()} is called, on both the clean and the crash path.
     */
    private static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (!server.isDedicatedServer() || !server.isStopped()) {
            return;
        }
        if (!NoHangConfig.ENABLED.get()) {
            return;
        }
        ShutdownWatchdog.noteVanillaSaveComplete(
                NoHangConfig.DELAY_SECONDS.get(),
                NoHangConfig.POST_SAVE_DELAY_SECONDS.get(),
                NoHangConfig.EXIT_CODE.get(),
                NoHangConfig.DUMP_THREADS_BEFORE_HALT.get());
    }
}
