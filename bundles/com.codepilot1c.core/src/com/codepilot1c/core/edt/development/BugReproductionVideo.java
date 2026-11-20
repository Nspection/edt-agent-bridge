// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.codepilot1c.core.edt.infobase.InfobasePaths;

/** Owns optional, local, silent recordings of a selected 1C process window. */
final class BugReproductionVideo {
    private static final int FPS = 4;
    private static final long FRAME_NS = TimeUnit.SECONDS.toNanos(1) / FPS;
    private static final int MAX_FRAMES = 10 * 60 * FPS;
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final int MAX_CLIPS = 4;
    private static final Semaphore SLOTS = new Semaphore(2);
    private static final Map<Path, Clip> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();

    private record Video(String id, String windowId, String state, int frames, int missed, String reason) {
        boolean live() {
            return "starting".equals(state) || "recording".equals(state) || "stopping".equals(state);
        }
    }

    private BugReproductionVideo() {}

    // Admission and stop requests are serialized by BugReproductionService.
    static void start(Path directory, long pid, String started, String window, String key) throws Exception {
        if (!key.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid idempotency_key");
        }
        String id = InfobasePaths.hash(key);
        var existing = read(directory, id);
        if (existing != null) {
            if (window != null && !window.equals(existing.windowId())) {
                throw new IllegalArgumentException("The video key has already been used for a different window");
            }
            return;
        }
        if (ACTIVE.containsKey(directory)) {
            throw new IllegalStateException("Video is already running in this session");
        }
        if (receipts(directory).size() >= MAX_CLIPS) {
            throw new IllegalStateException("The session already contains four video attempts");
        }
        if (!SLOTS.tryAcquire()) {
            throw new IllegalStateException("The recorder is busy");
        }
        var clip = new Clip(directory, pid, started, new Video(id, window, "starting", 0, 0, null));
        var lifecycle = RuntimeProcessLifecycle.global();
        boolean registered = false;
        try {
            lifecycle.own(lifecycle.epoch(), clip.resourceName(), clip::stop);
            registered = true;
            write(directory, clip.video);
            ACTIVE.put(directory, clip);
            var worker = new Thread(clip::record, "codepilot-bug-recording");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception | Error failure) {
            if (registered) lifecycle.forget(clip.resourceName());
            ACTIVE.remove(directory, clip);
            clip.completed.countDown();
            SLOTS.release();
            throw failure;
        }
    }

    static boolean exists(Path directory, String key) throws Exception {
        if (!key.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid idempotency_key");
        }
        return read(directory, InfobasePaths.hash(key)) != null;
    }

    static void stop(Path directory, String id) throws Exception {
        if (!id.matches("[a-f0-9]{64}") || read(directory, id) == null) {
            throw new IllegalArgumentException("Unknown video_id");
        }
        var clip = ACTIVE.get(directory);
        if (clip != null && id.equals(clip.video.id())) {
            clip.stop();
        }
    }

    static void stopActive(Path directory) throws Exception {
        var clip = ACTIVE.get(directory);
        if (clip != null) {
            clip.stop();
        }
    }

    static List<Map<String, Object>> receipts(Path directory) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().matches("video-[a-f0-9]{64}\\.json"))
                    .sorted().toList()) {
                String id = path.getFileName().toString().substring(6, 70);
                var clip = ACTIVE.get(directory);
                Video video = clip != null && clip.video.id().equals(id) ? clip.video : read(directory, id);
                if (video == null) {
                    continue;
                }
                var row = new LinkedHashMap<String, Object>();
                row.put("video_id", id);
                row.put("window_id", video.windowId());
                boolean active = clip != null && clip.video.id().equals(id);
                String state = video.state();
                if (video.live() && !active) {
                    state = "interrupted";
                } else if (video.live() && clip.stopping.getCount() == 0) {
                    state = "stopping";
                }
                row.put("state", state);
                row.put("frames", video.frames());
                row.put("missed_frames", video.missed());
                row.put("duration_ms", video.frames() * 1000L / FPS);
                row.put("audio", false);
                if (video.reason() != null) row.put("reason", video.reason());
                Path movie = directory.resolve("video-" + id + ".avi");
                if (Files.isRegularFile(movie, NOFOLLOW_LINKS)) row.put("path", movie.toString());
                result.add(row);
            }
        }
        return List.copyOf(result);
    }

    private static Video read(Path directory, String id) throws Exception {
        Path path = directory.resolve("video-" + id + ".json");
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null;
        Video video = BugReproductionService.read(path, Video.class, 8192);
        if (!id.equals(video.id())) throw new IllegalStateException("Video receipt identity changed");
        return video;
    }

    private static void write(Path directory, Video video) throws Exception {
        BugReproductionService.save(directory, "video-" + video.id() + ".json", video);
    }

    private static final class Clip {
        final Path directory;
        final long pid;
        final String started;
        final CountDownLatch stopping = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        volatile Video video;

        Clip(Path directory, long pid, String started, Video video) {
            this.directory = directory;
            this.pid = pid;
            this.started = started;
            this.video = video;
        }

        String resourceName() {
            return "bug-video:" + directory + ":" + video.id();
        }

        void stop() throws InterruptedException {
            stopping.countDown();
            if (!completed.await(7, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Video is still stopping; check the session status");
            }
        }

        void record() {
            Path pending = null;
            int frames = 0;
            int missed = 0;
            String reason = null;
            String state = "failed";
            try {
                pending = Files.createTempFile(directory, "video-", ".pending");
                var first = initialFrame();
                if (first == null) {
                    state = "stopped";
                } else {
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                    try (var output = new MjpegAvi(pending, first.width(), first.height(), FPS)) {
                        output.frame(first.jpeg());
                        frames = 1;
                        video = new Video(video.id(), video.windowId(), "recording", frames, 0, null);
                        int consecutiveFailures = 0;
                        long next = System.nanoTime() + FRAME_NS;
                        while (!stopping.await(Math.max(0, next - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                            if (frames >= MAX_FRAMES || output.bytes() >= MAX_BYTES || System.nanoTime() >= deadline) {
                                reason = "clip_limit";
                                break;
                            }
                            ProcessWindowCapture.VideoFrame frame;
                            try {
                                frame = capture();
                                if (frame.width() != first.width() || frame.height() != first.height()) {
                                    reason = "window_resized";
                                    break;
                                }
                            } catch (Exception unavailable) {
                                missed++;
                                if (++consecutiveFailures >= 4) {
                                    reason = "window_unavailable";
                                    break;
                                }
                                next = System.nanoTime() + FRAME_NS;
                                continue;
                            }
                            consecutiveFailures = 0;
                            output.frame(frame.jpeg());
                            video = new Video(video.id(), video.windowId(), "recording", ++frames, missed, null);
                            next = Math.max(next + FRAME_NS, System.nanoTime());
                        }
                    }
                    Files.move(pending, directory.resolve("video-" + video.id() + ".avi"), ATOMIC_MOVE);
                    state = "saved";
                }
            } catch (Exception failure) {
                reason = failure.getClass().getSimpleName();
            } finally {
                try {
                    if (pending != null) Files.deleteIfExists(pending);
                } catch (Exception ignored) {
                    // A pending file is never exposed as an attachment.
                }
                video = new Video(video.id(), video.windowId(), state, frames, missed, reason);
                try {
                    write(directory, video);
                } catch (Exception ignored) {
                    // The persisted starting receipt and the finalized file remain available for inspection.
                } finally {
                    ACTIVE.remove(directory, this);
                    RuntimeProcessLifecycle.global().forget(resourceName());
                    SLOTS.release();
                    completed.countDown();
                }
            }
        }

        private ProcessWindowCapture.VideoFrame initialFrame() throws Exception {
            for (int attempt = 0; stopping.getCount() > 0; attempt++) {
                try {
                    return capture();
                } catch (Exception unavailable) {
                    if (attempt == 3) throw unavailable;
                    stopping.await(FRAME_NS, TimeUnit.NANOSECONDS);
                }
            }
            return null;
        }

        private ProcessWindowCapture.VideoFrame capture() throws Exception {
            return ProcessWindowCapture.videoFrame(pid, started, video.windowId());
        }
    }
}
