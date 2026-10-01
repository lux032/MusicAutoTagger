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
    @BeforeEach void setup() throws Exception {
        root = Files.createTempDirectory(Files.createDirectories(Path.of("target/consumer-tests")), "case-");
        MusicConfig config = MusicConfig.getInstance();
        config.setMonitorDirectory(root.toString());
        config.setSupportedFormats(new String[]{"flac"});
        config.setDbType("file");
        config.setProcessedFileLogPath(root.resolve("processed.log").toString());
        logger = new ProcessedFileLogger(config, null);
        monitor = new FileMonitorService(config, logger);
    }
    @AfterEach void stop() { monitor.stop(); }
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
}
