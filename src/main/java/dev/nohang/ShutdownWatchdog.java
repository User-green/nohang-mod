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

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

final class ShutdownWatchdog {
    private static final Logger LOG = LogUtils.getLogger();

    private static volatile Thread armedThread;

    private ShutdownWatchdog() {}

    /**
     * Start a daemon thread that sleeps for {@code delaySeconds}, then — if the JVM is
     * still alive — logs a WARN (with an optional thread dump) and calls
     * {@link Runtime#halt(int)}.
     *
     * <p>Idempotent: a second call while a previous watchdog is still armed is a no-op.
     * The thread is marked daemon so it can never itself prevent JVM exit.
     *
     * <p>{@link Runtime#halt(int)} is used instead of {@link System#exit(int)} because
     * {@code exit()} runs shutdown hooks and would be blocked by the very hang we are
     * trying to escape.
     */
    static void arm(int delaySeconds, int exitCode, boolean dumpThreads) {
        synchronized (ShutdownWatchdog.class) {
            if (armedThread != null && armedThread.isAlive()) {
                LOG.warn("Shutdown watchdog already armed; ignoring duplicate arm()");
                return;
            }
            Thread t = new Thread(
                    () -> run(delaySeconds, exitCode, dumpThreads),
                    "nohang-shutdown-watchdog");
            t.setDaemon(true);
            armedThread = t;
            t.start();
            LOG.info("Shutdown watchdog armed: halt in {}s (exitCode={}, dumpThreads={}) if JVM is still alive",
                    delaySeconds, exitCode, dumpThreads);
        }
    }

    private static void run(int delaySeconds, int exitCode, boolean dumpThreads) {
        Instant armedAt = Instant.now();
        try {
            Thread.sleep(Duration.ofSeconds(delaySeconds));
        } catch (InterruptedException ie) {
            // Nothing in this mod interrupts the watchdog; treat an interrupt from anywhere
            // else as an instruction to stand down rather than as proof of a clean exit.
            Thread.currentThread().interrupt();
            LOG.warn("Shutdown watchdog interrupted after {}s; standing down without halting",
                    Duration.between(armedAt, Instant.now()).toSeconds());
            return;
        }

        long elapsed = Duration.between(armedAt, Instant.now()).toSeconds();
        LOG.warn("JVM still alive {}s after shutdown began; configured delay was {}s. "
                        + "Forcing halt — likely culprit: fucksable-1.6.8 mixin "
                        + "SubLevelHoldingChunkMap.awaitBeforeClose (see SPEC.md evidence/).",
                elapsed, delaySeconds);

        if (dumpThreads) {
            dumpAllThreads();
        }

        LOG.warn("Calling Runtime.halt({})", exitCode);
        Runtime.getRuntime().halt(exitCode);
    }

    /**
     * Log every live thread's stack as a <em>single</em> log event. Emitting one event per
     * frame would interleave the dump with whatever the still-running threads are logging
     * during shutdown, shredding the one artifact this is here to produce.
     */
    private static void dumpAllThreads() {
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("--- thread dump (").append(traces.size()).append(" threads) ---");
        for (Map.Entry<Thread, StackTraceElement[]> e : traces.entrySet()) {
            Thread thread = e.getKey();
            sb.append(System.lineSeparator())
                    .append("  \"").append(thread.getName()).append("\"")
                    .append(" id=").append(thread.threadId())
                    .append(" daemon=").append(thread.isDaemon())
                    .append(" state=").append(thread.getState())
                    .append(" priority=").append(thread.getPriority());
            ThreadGroup group = thread.getThreadGroup();
            if (group != null) {
                sb.append(" group=\"").append(group.getName()).append("\"");
            }
            for (StackTraceElement el : e.getValue()) {
                sb.append(System.lineSeparator()).append("        at ").append(el);
            }
        }
        sb.append(System.lineSeparator()).append("--- end of thread dump ---");
        LOG.warn("{}", sb);
    }
}
