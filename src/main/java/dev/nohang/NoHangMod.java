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
import net.neoforged.neoforge.common.NeoForge;
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
        }
        LOG.info("NoHang config {} ({}): enabled={}, delaySeconds={}, exitCode={}, dumpThreadsBeforeHalt={}",
                what,
                config.getFileName(),
                NoHangConfig.ENABLED.get(),
                delay,
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
        int exitCode = NoHangConfig.EXIT_CODE.get();
        boolean dump = NoHangConfig.DUMP_THREADS_BEFORE_HALT.get();
        LOG.warn("ServerStoppingEvent fired; arming shutdown watchdog (delay={}s, exitCode={}, dumpThreads={}). "
                + "If the JVM is still alive after {}s, Runtime.halt({}) will be called.",
                delay, exitCode, dump, delay, exitCode);
        ShutdownWatchdog.arm(delay, exitCode, dump);
    }
}
