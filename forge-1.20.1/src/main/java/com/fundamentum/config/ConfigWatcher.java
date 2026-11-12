package com.fundamentum.config;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells {@link FConfig} when a config folder changed, through the operating system's file notifications, so the files
 * are only read again after something wrote to them. A daemon thread waits for the notifications and sets a flag the
 * server tick reads. Watches each config file's folder and the folders above it up to the config root, so a folder made
 * later is noticed too. The same file on every version.
 */
final class ConfigWatcher {
    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    private final WatchService service;
    private final Set<Path> watched = new HashSet<>();
    private volatile boolean changed;

    private ConfigWatcher(WatchService service) {
        this.service = service;
        Thread t = new Thread(this::run, "Fundamentum config watcher");
        t.setDaemon(true);
        t.start();
    }

    /** A running watcher, or null when the file system cannot watch (the timer safety net still works). */
    static ConfigWatcher start() {
        try {
            return new ConfigWatcher(FileSystems.getDefault().newWatchService());
        } catch (IOException | UnsupportedOperationException e) {
            LOG.warn("Config files cannot be watched, only the timer will notice edits: {}", e.toString());
            return null;
        }
    }

    private void run() {
        try {
            while (true) {
                WatchKey key = service.take();
                key.pollEvents();
                changed = true;
                key.reset();
            }
        } catch (ClosedWatchServiceException | InterruptedException ended) {
            // the server stopped
        }
    }

    /** Watches the folder of {@code file} and every folder above it up to {@code root}, as far as they exist. */
    synchronized boolean watch(Path file, Path root) {
        if (file == null || root == null) return false;
        boolean added = false;
        Path dir = file.toAbsolutePath().normalize().getParent();
        Path stop = root.toAbsolutePath().normalize();
        while (dir != null && dir.startsWith(stop)) {
            if (!watched.contains(dir) && Files.isDirectory(dir)) {
                try {
                    dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                    watched.add(dir);
                    added = true;
                } catch (IOException | ClosedWatchServiceException e) {
                    LOG.debug("Could not watch {}: {}", dir, e.toString());
                }
            }
            if (dir.equals(stop)) break;
            dir = dir.getParent();
        }
        return added;
    }

    /** True once since the last call when a watched folder changed. */
    boolean takeChanged() {
        if (!changed) return false;
        changed = false;
        return true;
    }

    void close() {
        try {
            service.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
