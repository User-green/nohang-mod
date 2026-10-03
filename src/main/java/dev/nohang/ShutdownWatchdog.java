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

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Forces the JVM to exit when a shutdown wedges.
 *
 * <p>Two deadlines run at once, and whichever comes first wins:
 *
 * <ul>
 *   <li><b>Save-completion deadline</b> (the normal trigger) — {@code postSaveDelaySeconds}
 *       after the vanilla world save is confirmed durable. See
 *       {@link #noteVanillaSaveComplete}.
 *   <li><b>Absolute bound</b> (the backstop) — {@code delaySeconds} after shutdown began,
 *       used when the save is never confirmed, i.e. when the hang moved <em>ahead</em> of
 *       the save instead of after it.
 * </ul>
 */
final class ShutdownWatchdog {
    private static final Logger LOG = LogUtils.getLogger();

    /** How often the watchdog re-checks its deadlines while waiting. */
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    /** Guarded by {@code ShutdownWatchdog.class}. */
    private static Thread armedThread;

    /**
     * Set once the vanilla save is confirmed durable. Written under the class lock, and
     * written <em>after</em> {@link #saveCompleteNanos}, so a reader that sees this true
     * also sees the timestamp.
     */
    private static volatile boolean saveComplete;
    private static volatile long saveCompleteNanos;

    private ShutdownWatchdog() {}

    /**
     * Arm the watchdog at the start of shutdown, from {@code ServerStoppingEvent}.
     *
     * <p>Idempotent: a second call while a previous watchdog is still armed is a no-op.
     * The thread is marked daemon so it can never itself prevent JVM exit.
     *
     * <p>{@link Runtime#halt(int)} is used instead of {@link System#exit(int)} because
     * {@code exit()} runs shutdown hooks and would be blocked by the very hang we are
     * trying to escape.
     */
    static void arm(int delaySeconds, int postSaveDelaySeconds, int exitCode, boolean dumpThreads) {
        synchronized (ShutdownWatchdog.class) {
            if (armedThread != null && armedThread.isAlive()) {
                LOG.warn("Shutdown watchdog already armed; ignoring duplicate arm()");
                return;
            }
            // A fresh shutdown: any save-confirmation left over from a previous server in
            // this JVM must not shorten this one's deadline.
            saveComplete = false;
            saveCompleteNanos = 0L;
            start(delaySeconds, postSaveDelaySeconds, exitCode, dumpThreads);
        }
    }

    /**
     * Record that the vanilla world save is durable, and arm the watchdog if it is not
     * already armed.
     *
     * <p>Called from the first {@code LevelEvent.Unload} of a shutdown. Vanilla posts that
     * event in {@code MinecraftServer.stopServer} directly after {@code saveAllChunks()}
     * has returned — every level's chunks written, {@code level.dat} written last — and
     * directly before {@code ServerLevel.close()}, which is where the fucksable mixin
     * wedges. So it is the latest signal available that is still ahead of the hang.
     *
     * <p>Arming here also covers the <b>crash path</b>: when the server thread dies of an
     * exception, {@code MinecraftServer.runServer} skips {@code handleServerStopping} (so
     * {@code ServerStoppingEvent} never fires) but still calls {@code stopServer()} from
     * its {@code finally} block, where it can wedge exactly as it does on a clean stop.
     */
    static void noteVanillaSaveComplete(int delaySeconds, int postSaveDelaySeconds, int exitCode, boolean dumpThreads) {
        synchronized (ShutdownWatchdog.class) {
            if (saveComplete) {
                return; // one level per dimension posts Unload; only the first one matters
            }
            saveCompleteNanos = System.nanoTime();
            saveComplete = true;
            LOG.info("Vanilla world save confirmed durable (saveAllChunks returned, level.dat written); "
                    + "halting in {}s unless the JVM exits on its own first", postSaveDelaySeconds);

            if (armedThread == null || !armedThread.isAlive()) {
                LOG.warn("Watchdog was not armed when the save completed — ServerStoppingEvent never fired, "
                        + "which means this is a crash-path shutdown (MinecraftServer.runServer reaches "
                        + "stopServer() through its finally block). Arming now.");
                start(delaySeconds, postSaveDelaySeconds, exitCode, dumpThreads);
            }
        }
    }

    /** Caller must hold the class lock. */
    private static void start(int delaySeconds, int postSaveDelaySeconds, int exitCode, boolean dumpThreads) {
        long armedAtNanos = System.nanoTime();
        Thread t = new Thread(
                () -> run(armedAtNanos, delaySeconds, postSaveDelaySeconds, exitCode, dumpThreads),
                "nohang-shutdown-watchdog");
        t.setDaemon(true);
        armedThread = t;
        t.start();
        LOG.info("Shutdown watchdog armed: halt {}s after the world save is durable, "
                        + "or {}s from now at the latest (exitCode={}, dumpThreads={})",
                postSaveDelaySeconds, delaySeconds, exitCode, dumpThreads);
    }

    private static void run(long armedAtNanos, int delaySeconds, int postSaveDelaySeconds,
            int exitCode, boolean dumpThreads) {
        final long absoluteDeadline = armedAtNanos + TimeUnit.SECONDS.toNanos(delaySeconds);
        long deadline = absoluteDeadline;
        boolean sawSave = false;

        while (true) {
            if (!sawSave && saveComplete) {
                sawSave = true;
                long saveDeadline = saveCompleteNanos + TimeUnit.SECONDS.toNanos(postSaveDelaySeconds);
                // Never let the save-completion trigger push the halt out past the backstop.
                if (saveDeadline - absoluteDeadline < 0) {
                    deadline = saveDeadline;
                }
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(remaining, POLL_NANOS)));
            } catch (InterruptedException ie) {
                // Nothing in this mod interrupts the watchdog; treat an interrupt from anywhere
                // else as an instruction to stand down rather than as proof of a clean exit.
                Thread.currentThread().interrupt();
                LOG.warn("Shutdown watchdog interrupted after {}s; standing down without halting",
                        elapsedSeconds(armedAtNanos));
                return;
            }
        }

        if (sawSave) {
            LOG.warn("JVM still alive {}s after shutdown began, {}s after the world save became durable. "
                            + "Forcing halt — likely culprit: fucksable-1.6.8 mixin "
                            + "SubLevelHoldingChunkMap.awaitBeforeClose (see SPEC.md evidence/).",
                    elapsedSeconds(armedAtNanos), elapsedSeconds(saveCompleteNanos));
        } else {
            LOG.warn("Absolute bound of {}s reached and the vanilla world save was NEVER confirmed durable "
                            + "(no LevelEvent.Unload seen). The hang is ahead of the save, not after it, so "
                            + "this halt may interrupt it — check world/level.dat and the region files, and "
                            + "see SPEC.md §6. Forcing halt anyway; the supervisor would SIGKILL regardless.",
                    delaySeconds);
        }

        if (dumpThreads) {
            dumpAllThreads();
        }

        LOG.warn("Calling Runtime.halt({})", exitCode);
        Runtime.getRuntime().halt(exitCode);
    }

    private static long elapsedSeconds(long sinceNanos) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - sinceNanos);
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
