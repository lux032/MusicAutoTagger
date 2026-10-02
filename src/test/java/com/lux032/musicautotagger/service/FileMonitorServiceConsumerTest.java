package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessResult;
import org.junit.jupiter.api.*;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FileMonitorServiceConsumerTest {
    Path root;
    FileMonitorService monitor;
    ProcessedFileLogger logger;
    String previousFailedDirectory;
    @BeforeEach void setup() throws Exception {
        root = Files.createTempDirectory(Files.createDirectories(Path.of("target/consumer-tests")), "case-");
        MusicConfig config = MusicConfig.getInstance();
        previousFailedDirectory = config.getFailedDirectory();
        config.setMonitorDirectory(root.toString());
        config.setSupportedFormats(new String[]{"flac"});
        config.setDbType("file");
        config.setProcessedFileLogPath(root.resolve("processed.log").toString());
        logger = new ProcessedFileLogger(config, null);
        monitor = new FileMonitorService(config, logger);
    }
    @AfterEach void stop() {
        try { monitor.stop(); }
        finally { MusicConfig.getInstance().setFailedDirectory(previousFailedDirectory); }
    }
    File file(String name) throws Exception {
        // These are queue tests; prevent watch events from adding duplicate inputs.
        Field keys = FileMonitorService.class.getDeclaredField("watchKeys");
        keys.setAccessible(true);
        ((Map<?, ?>) keys.get(monitor)).clear();
        return Files.writeString(root.resolve(name + ".flac"), "audio").toFile();
    }
    @SuppressWarnings("unchecked") String state(String task) {
        return (String) ((Map<String, Map<String, Object>>) monitor.getHealthSnapshot().get("tasks")).get(task).get("state");
    }
    void awaitState(String task, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!state.equals(state(task)) && System.nanoTime() < deadline) Thread.yield();
        assertEquals(state, state(task));
    }
    @Test void runtimeFailureRecordedAndNextFileRuns() throws Exception {
        CountDownLatch next = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        monitor.setFileReadyCallbackWithResult(f -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("before/finally failure");
            next.countDown(); return ProcessResult.SUCCESS;
        });
        monitor.start();
        File first = file("first"), second = file("second");
        monitor.requeueFiles(List.of(first, second));
        assertTrue(next.await(8, TimeUnit.SECONDS));
        assertTrue(logger.isFileProcessed(first));
        assertTrue(Files.readString(root.resolve("processed.log")).contains("FAILED"));
        assertEquals(2, calls.get());
        assertNotEquals("FAULTED", state("consumer"));
    }
    @Test void simulatedErrorFaultsWithoutFailedOrNextConsumption() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        monitor.setFileReadyCallbackWithResult(f -> { calls.incrementAndGet(); throw new OutOfMemoryError("simulated only"); });
        monitor.start();
        File first = file("first"), second = file("second");
        monitor.requeueFiles(List.of(first, second));
        awaitState("consumer", "FAULTED");
        assertFalse(logger.isFileProcessed(first));
        assertEquals(1, calls.get());
        assertFalse(monitor.isConsumerAvailable());
        assertThrows(IllegalStateException.class, () -> monitor.requeueFiles(List.of(second)));
        assertTrue(monitor.getHealthSnapshot().get("lastFault").toString().contains("OutOfMemoryError"));
        assertFalse(monitor.getHealthSnapshot().toString().contains("simulated only"));
    }
    @Test void retryHasRuntimeIsolationAndFatalBoundary() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        monitor.setFileReadyCallbackWithResult(f -> {
            if (calls.incrementAndGet() == 1) { first.countDown(); throw new IllegalArgumentException("retry ordinary"); }
            throw new OutOfMemoryError("retry simulated");
        });
        monitor.start();
        // Exercise retry deterministically, without filesystem events enqueuing the same files in main.
        Field keys = FileMonitorService.class.getDeclaredField("watchKeys");
        keys.setAccessible(true); ((Map<?, ?>) keys.get(monitor)).clear();
        File ordinary = file("ordinary"), fatal = file("fatal");
        Method enqueue = FileMonitorService.class.getDeclaredMethod("addToFailedQueue", File.class, int.class);
        enqueue.setAccessible(true);
        enqueue.invoke(monitor, ordinary, 0);
        assertTrue(first.await(8, TimeUnit.SECONDS));
        enqueue.invoke(monitor, fatal, 0);
        awaitState("retry", "FAULTED");
        assertTrue(logger.isFileProcessed(ordinary));
        assertFalse(logger.isFileProcessed(fatal));
        assertEquals(2, calls.get());
    }
    @Test void pauseResumeAndStopNeverCreateAnotherConsumer() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        monitor.setFileReadyCallbackWithResult(f -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max); entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
            return ProcessResult.SUCCESS;
        });
        monitor.start(); monitor.start(); monitor.pause();
        monitor.requeueFiles(List.of(file("one")));
        monitor.resume();
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        monitor.stop(); release.countDown();
        awaitState("consumer", "STOPPED");
        assertEquals(1, maximum.get());
        assertThrows(IllegalStateException.class, monitor::start);
    }
    @Test void unavailableAdmissionDoesNotExecuteRecordDeletion() throws Exception {
        AtomicInteger deleted = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> monitor.requeueFiles(List.of(), deleted::incrementAndGet));
        assertEquals(0, deleted.get());
        monitor.start(); monitor.stop();
        assertThrows(IllegalStateException.class, () -> monitor.requeueFiles(List.of(), deleted::incrementAndGet));
        assertEquals(0, deleted.get());
    }
    @Test void reidentifyFaultKeepsExistingRecord() throws Exception {
        monitor.setFileReadyCallbackWithResult(f -> { throw new OutOfMemoryError("test"); });
        monitor.start();
        File source = file("record");
        logger.markFileAsProcessed(source, "recording", "Artist", "Title", "Album");
        monitor.requeueFiles(List.of(source));
        awaitState("consumer", "FAULTED");
        ProcessedRecordService service = new ProcessedRecordService(MusicConfig.getInstance(), logger, null, null, null, () -> monitor);
        var targets = logger.findRecordsByPaths(List.of(source.getAbsolutePath()));
        assertEquals(1, targets.size());
        assertThrows(IllegalStateException.class, () -> service.reidentify(targets));
        assertTrue(logger.isFileProcessed(source));
    }
    @Test void stopAdmissionRaceDoesNotDeleteAfterStop() throws Exception {
        monitor.start();
        CountDownLatch deletionEntered = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AtomicInteger deleted = new AtomicInteger();
        Thread admission = new Thread(() -> monitor.requeueFiles(List.of(), () -> {
            deletionEntered.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            deleted.incrementAndGet();
        }));
        admission.start(); assertTrue(deletionEntered.await(5, TimeUnit.SECONDS));
        Thread stopper = new Thread(() -> { monitor.stop(); stopped.countDown(); });
        stopper.start(); release.countDown();
        admission.join(5000); assertTrue(stopped.await(8, TimeUnit.SECONDS));
        assertEquals(1, deleted.get());
        assertThrows(IllegalStateException.class, () -> monitor.requeueFiles(List.of(), deleted::incrementAndGet));
        assertEquals(1, deleted.get());
    }
    @Test void persistenceFailureIsRetainedAndNextFileContinues() throws Exception {
        CountDownLatch next = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        monitor.setFileReadyCallbackWithResult(f -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("ordinary");
            next.countDown(); return ProcessResult.SUCCESS;
        });
        monitor.start();
        MusicConfig.getInstance().setProcessedFileLogPath(root.toString()); // directory cannot be appended as a file
        monitor.requeueFiles(List.of(file("first"), file("second")));
        assertTrue(next.await(8, TimeUnit.SECONDS));
        assertEquals(1, monitor.getHealthSnapshot().get("unrecordedFailureCount"));
        assertNotEquals("FAULTED", state("consumer"));
    }
    @Test void missingStartupDirectoryIsNotRunning() throws Exception {
        monitor.stop();
        MusicConfig.getInstance().setMonitorDirectory(root.resolve("missing").toString());
        monitor = new FileMonitorService(MusicConfig.getInstance(), logger);
        monitor.start(); assertFalse(monitor.isRunning()); assertEquals("NOT_STARTED", state("consumer"));
    }
    @Test void nullResultIsRecordedAsFailure() throws Exception {
        monitor.setFileReadyCallbackWithResult(f -> null);
        monitor.start(); File first = file("null"); monitor.requeueFiles(List.of(first));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!logger.isFileProcessed(first) && System.nanoTime() < deadline) Thread.yield();
        assertTrue(logger.isFileProcessed(first));
        assertNotEquals("FAULTED", state("consumer"));
    }

    @Test void reviewPrefixDrainsQuicklyAndNormalCallsKeepInterval() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        List<Long> starts = new CopyOnWriteArrayList<>();
        monitor.setAutoProcessingSkipPredicate(f -> f.getName().startsWith("a-review"));
        monitor.setFileReadyCallbackWithResult(f -> {
            assertFalse(f.getName().startsWith("a-review"));
            starts.add(System.nanoTime()); done.countDown(); return ProcessResult.SUCCESS;
        });
        monitor.start();
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) files.add(file("a-review" + i));
        files.add(file("b-normal")); files.add(file("c-normal"));
        long before = System.nanoTime(); monitor.requeueFiles(files);
        assertTrue(done.await(7, TimeUnit.SECONDS));
        assertEquals(2, starts.size());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(starts.get(0) - before) < 4000);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(starts.get(1) - starts.get(0)) >= 1900);
        for (int i = 0; i < 5; i++) assertFalse(logger.isFileProcessed(files.get(i)));
    }

    @Test void exhaustedReviewRetriesNeverRecordCopyOrProcessAndDoNotWait() throws Exception {
        MusicConfig.getInstance().setFailedDirectory(root.resolve("failed").toString());
        CountDownLatch done = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        monitor.setAutoProcessingSkipPredicate(f -> f.getName().startsWith("review"));
        monitor.setFileReadyCallbackWithResult(f -> {
            calls.incrementAndGet(); done.countDown(); return ProcessResult.SUCCESS;
        });
        monitor.start();
        Method enqueue = FileMonitorService.class.getDeclaredMethod("addToFailedQueue", File.class, int.class);
        enqueue.setAccessible(true);
        List<File> reviews = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            File f = file("review" + i); reviews.add(f); enqueue.invoke(monitor, f, Integer.MAX_VALUE);
        }
        enqueue.invoke(monitor, file("ordinary"), 0);
        assertTrue(done.await(4, TimeUnit.SECONDS)); assertEquals(1, calls.get());
        for (File f : reviews) { assertTrue(f.exists()); assertFalse(logger.isFileProcessed(f)); }
        assertFalse(Files.exists(root.resolve("failed")));
    }

    @Test void predicateRuntimeExceptionFallsBackToOrdinaryProcessing() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        monitor.setAutoProcessingSkipPredicate(f -> { throw new IllegalStateException("review unavailable"); });
        monitor.setFileReadyCallbackWithResult(f -> { done.countDown(); return ProcessResult.SUCCESS; });
        monitor.start(); monitor.requeueFiles(List.of(file("a"), file("b")));
        assertTrue(done.await(7, TimeUnit.SECONDS));
        assertNotEquals("FAULTED", state("consumer")); assertNotEquals("FAULTED", state("retry"));
    }

    @Test void dynamicReviewStateSkipsRemainingQueuedFiles() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch first = new CountDownLatch(1), release = new CountDownLatch(1), last = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        monitor.setAutoProcessingSkipPredicate(f -> pending.get() && f.getName().startsWith("b-review"));
        monitor.setFileReadyCallbackWithResult(f -> {
            calls.incrementAndGet();
            if (f.getName().startsWith("a")) {
                first.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } else last.countDown();
            return ProcessResult.SUCCESS;
        });
        monitor.start(); List<File> files = new ArrayList<>(); files.add(file("a-trigger"));
        for (int i = 0; i < 5; i++) files.add(file("b-review" + i));
        files.add(file("c-last")); monitor.requeueFiles(files);
        assertTrue(first.await(5, TimeUnit.SECONDS)); pending.set(true); release.countDown();
        assertTrue(last.await(5, TimeUnit.SECONDS)); assertEquals(2, calls.get());
    }

    @Test void noPredicatePreservesProcessingAndInterval() throws Exception {
        CountDownLatch done = new CountDownLatch(2); List<Long> starts = new CopyOnWriteArrayList<>();
        monitor.setFileReadyCallbackWithResult(f -> {
            starts.add(System.nanoTime()); done.countDown(); return ProcessResult.SUCCESS;
        });
        monitor.start(); monitor.requeueFiles(List.of(file("a-review"), file("b-review")));
        assertTrue(done.await(7, TimeUnit.SECONDS)); assertEquals(2, starts.size());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(starts.get(1) - starts.get(0)) >= 1900);
    }

    @Test void automaticAdmissionFiltersAfterProcessedCheck() throws Exception {
        AtomicInteger checks = new AtomicInteger(), calls = new AtomicInteger();
        monitor.setAutoProcessingSkipPredicate(f -> { checks.incrementAndGet(); return true; });
        monitor.setFileReadyCallbackWithResult(f -> { calls.incrementAndGet(); return ProcessResult.SUCCESS; });
        monitor.start(); File processed = file("processed"), pending = file("pending");
        logger.markFileAsProcessed(processed, "id", "artist", "title", "album");
        Method admission = FileMonitorService.class.getDeclaredMethod("addToQueue", Path.class, boolean.class);
        admission.setAccessible(true); admission.invoke(monitor, processed.toPath(), false);
        assertEquals(0, checks.get()); admission.invoke(monitor, pending.toPath(), false);
        assertEquals(1, checks.get()); assertEquals(0, monitor.getQueueSize()); assertEquals(0, calls.get());
    }

    @Test void nestedMultidiscScanFiltersOncePerDirectoryAndCountsRealAlbums() throws Exception {
        Path album = Files.createDirectories(root.resolve("Artist/Album"));
        Path disc1 = Files.createDirectories(album.resolve("Disc 1"));
        Path disc2 = Files.createDirectories(album.resolve("CD2"));
        Path other = Files.createDirectories(root.resolve("Artist/Other"));
        File processed = Files.writeString(disc1.resolve("processed.flac"), "audio").toFile();
        logger.markFileAsProcessed(processed, "id", "artist", "title", "album");
        Files.writeString(disc1.resolve("one.flac"), "audio"); Files.writeString(disc1.resolve("two.flac"), "audio");
        Files.writeString(disc2.resolve("three.flac"), "audio"); Files.writeString(other.resolve("four.flac"), "audio");
        com.lux032.musicautotagger.util.FileSystemUtils fs =
            new com.lux032.musicautotagger.util.FileSystemUtils(MusicConfig.getInstance());
        Map<String, Integer> checks = new ConcurrentHashMap<>();
        monitor.setAutoProcessingSkipPredicate(f -> {
            checks.merge(f.getParent(), 1, Integer::sum);
            File albumRoot = fs.getAlbumRootDirectory(f);
            assertTrue(Set.of(album.toFile().getAbsolutePath(), other.toFile().getAbsolutePath()).contains(albumRoot.getAbsolutePath()));
            return true;
        });
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream capture = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(capture); monitor.start();
        } finally { System.setErr(original); }
        assertEquals(3, checks.size()); assertTrue(checks.values().stream().allMatch(n -> n == 1));
        assertEquals(0, monitor.getQueueSize());
        String summary = org.slf4j.helpers.MessageFormatter.arrayFormat(
            com.lux032.musicautotagger.util.I18nUtil.getMessage("monitor.scan.review.summary"),
            new Object[]{1, 0, 2, 4}).getMessage();
        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains(summary), output.toString());
    }

    Object privateField(String name) throws Exception {
        Field field = FileMonitorService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(monitor);
    }

    void awaitBlockedIn(String method) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                if (entry.getKey().getState() == Thread.State.BLOCKED
                    && Arrays.stream(entry.getValue()).anyMatch(frame ->
                        frame.getClassName().equals(FileMonitorService.class.getName())
                            && frame.getMethodName().equals(method))) return;
            }
            Thread.sleep(10);
        }
        fail("No consumer blocked in " + method);
    }

    @SuppressWarnings("unchecked") BlockingQueue<Object> retryQueue() throws Exception {
        return (BlockingQueue<Object>) privateField("failedFileQueue");
    }

    Object retryItem(File file, int count) throws Exception {
        Class<?> type = Class.forName(FileMonitorService.class.getName() + "$FailedFile");
        Constructor<?> constructor = type.getDeclaredConstructor(File.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(file, count);
    }

    @Test void pauseAfterMainPollPreservesOrder() throws Exception { assertPauseAfterPollOrder(false); }
    @Test void pauseAfterRetryPollPreservesOrder() throws Exception { assertPauseAfterPollOrder(true); }

    void assertPauseAfterPollOrder(boolean retry) throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1);
        monitor.setFileReadyCallbackWithResult(f -> {
            calls.add(f.getName());
            if (calls.size() == 1) first.countDown(); else second.countDown();
            return ProcessResult.SUCCESS;
        });
        monitor.start(); File a = file("a"), b = file("b");
        synchronized (privateField("processingLock")) {
            if (retry) {
                retryQueue().offer(retryItem(a, 0)); retryQueue().offer(retryItem(b, 0));
            } else monitor.requeueFiles(List.of(a, b));
            awaitBlockedIn(retry ? "processFailedFileQueue" : "processOne");
            monitor.pause();
        }
        assertTrue(first.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("a.flac"), calls);
        assertEquals(1, retry ? retryQueue().size() : monitor.getQueueSize());
        assertFalse(second.await(250, TimeUnit.MILLISECONDS));
        // Prevent resume's existing rescan from duplicating these synthetic queue inputs.
        Files.delete(a.toPath()); Files.delete(b.toPath()); monitor.resume();
        assertTrue(second.await(6, TimeUnit.SECONDS));
        assertEquals(List.of("a.flac", "b.flac"), calls);
    }

    @Test void consumerFaultAfterRetryPollRetainsExhaustedItemAndCount() throws Exception {
        MusicConfig.getInstance().setFailedDirectory(root.resolve("failed").toString());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        monitor.setFileReadyCallbackWithResult(f -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new OutOfMemoryError("deterministic consumer fault");
        });
        monitor.start(); File fatal = file("fatal"), exhausted = file("exhausted");
        Object original = retryItem(exhausted, Integer.MAX_VALUE);
        monitor.requeueFiles(List.of(fatal));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            retryQueue().offer(original);
            awaitBlockedIn("processFailedFileQueue");
            assertEquals(0, retryQueue().size());
        } finally { release.countDown(); }
        awaitState("consumer", "FAULTED");
        awaitRetainedRetry(original, exhausted);
        // Longer than the normal retry interval: the fault gate must not keep re-offering.
        Thread.sleep(2200);
        assertEquals(1, retryQueue().size()); assertSame(original, retryQueue().peek());
        assertEquals(1, calls.get()); assertFalse(logger.isFileProcessed(exhausted));
        assertFalse(Files.exists(root.resolve("failed")));
    }

    void awaitRetainedRetry(Object original, File file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (retryQueue().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, retryQueue().size()); assertSame(original, retryQueue().peek());
        Method getFile = original.getClass().getDeclaredMethod("getFile"); getFile.setAccessible(true);
        Method getCount = original.getClass().getDeclaredMethod("getRetryCount"); getCount.setAccessible(true);
        assertEquals(file, getFile.invoke(original));
        assertEquals(Integer.MAX_VALUE, getCount.invoke(original));
    }

    @Test void stopAfterRetryPollRetainsExhaustedItem() throws Exception {
        MusicConfig.getInstance().setFailedDirectory(root.resolve("failed").toString());
        monitor.start(); File exhausted = file("exhausted");
        AtomicInteger calls = new AtomicInteger();
        monitor.setFileReadyCallbackWithResult(f -> { calls.incrementAndGet(); return ProcessResult.SUCCESS; });
        Object original = retryItem(exhausted, Integer.MAX_VALUE);
        Thread stopper;
        synchronized (privateField("processingLock")) {
            retryQueue().offer(original);
            awaitBlockedIn("processFailedFileQueue");
            stopper = new Thread(monitor::stop); stopper.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (monitor.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(monitor.isRunning());
        }
        stopper.join(8000); assertFalse(stopper.isAlive());
        awaitRetainedRetry(original, exhausted);
        assertEquals(0, calls.get()); assertFalse(logger.isFileProcessed(exhausted));
        assertFalse(Files.exists(root.resolve("failed")));
    }

    @Test void fileEventAdmissionSkipsReviewBeforeStabilityWait() throws Exception {
        AtomicInteger calls = new AtomicInteger(), checks = new AtomicInteger();
        monitor.setAutoProcessingSkipPredicate(f -> { checks.incrementAndGet(); return true; });
        monitor.setFileReadyCallbackWithResult(f -> { calls.incrementAndGet(); return ProcessResult.SUCCESS; });
        monitor.start(); File pending = file("event-review");
        Method admission = FileMonitorService.class.getDeclaredMethod("addToQueue", Path.class, boolean.class);
        admission.setAccessible(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> admitted = executor.submit(() -> {
                try { admission.invoke(monitor, pending.toPath(), true); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            });
            // Normal stabilization requires at least two seconds for this nonempty file.
            admitted.get(1500, TimeUnit.MILLISECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals(1, checks.get()); assertEquals(0, calls.get());
        assertEquals(0, monitor.getQueueSize()); assertFalse(logger.isFileProcessed(pending));
    }
}
