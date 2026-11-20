package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OnlineSearchQueueServiceTest {

    @TempDir
    Path tempDir;

    private MusicConfig createConfig(int concurrency) throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setReviewQueuePath(tempDir.resolve("review-queue.json").toString());
        cfg.setReviewStagingDirectory(tempDir.resolve("review-staging").toString());
        cfg.setSupportedFormats(new String[]{"flac", "mp3"});
        cfg.setLlmWebSearchMaxConcurrency(concurrency);
        cfg.setReviewMissingTracksAutoOnlineSearch(true);
        return cfg;
    }

    private TagWriterService stubTagWriter(MusicConfig cfg) {
        return new TagWriterService(cfg) {
            @Override
            public com.lux032.musicautotagger.model.MusicMetadata readTags(File file) {
                return new com.lux032.musicautotagger.model.MusicMetadata();
            }
        };
    }

    private ReviewItem enqueueDummyReview(ReviewQueueService queue, String folderName) throws Exception {
        Path folder = tempDir.resolve(folderName);
        Files.createDirectories(folder);
        Path audio = folder.resolve("01.flac");
        Files.write(audio, new byte[]{1, 2, 3});
        return queue.enqueueRecoveryFolder(folder.toString(), null, List.of(audio.toFile()), null, "hash-" + folderName);
    }

    private WebSearchClient.SearchResponse validResponse(String title, boolean needsRound2) {
        WebSearchClient.SearchResponse resp = new WebSearchClient.SearchResponse();
        resp.setProvider("tavily+test");
        resp.setModel("test-model");
        resp.addCitation(WebSearchClient.Citation.of("https://musicbrainz.org/release/abc", title, "snippet"));
        resp.setText("{\"needs_second_round\":" + needsRound2 + ",\"candidates\":[{"
            + "\"title\":\"" + title + "\",\"artist\":\"Tester\",\"album_artist\":\"Tester\","
            + "\"release_date\":\"2026-01-01\",\"confidence\":0.95,"
            + "\"tracks\":[{\"disc\":1,\"track\":1,\"title\":\"Track 1\",\"artist\":\"Tester\",\"duration\":120}],"
            + "\"source_urls\":[\"https://musicbrainz.org/release/abc\"]"
            + "}],\"clues\":[]}");
        return resp;
    }

    @Test
    void serialQueueShowsExplicitPositionsAndManualPreemptsAuto() throws Exception {
        MusicConfig cfg = createConfig(1);
        ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
        ReviewItem item1 = enqueueDummyReview(reviewQueue, "AlbumA");
        ReviewItem item2 = enqueueDummyReview(reviewQueue, "AlbumB");
        ReviewItem item3 = enqueueDummyReview(reviewQueue, "AlbumC");
        item2.setKind(ReviewItem.Kind.TRACKS_MISSING);
        reviewQueue.update(item2);

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        WebSearchClient client = new WebSearchClient() {
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override public String name() { return "tavily"; }
            @Override public SearchResponse search(String sys, String usr) {
                return search(sys, usr, null);
            }
            @Override public SearchResponse search(String sys, String usr, ProgressListener progress) {
                String folder = usr.contains("AlbumA") ? "AlbumA" : usr.contains("AlbumB") ? "AlbumB" : "AlbumC";
                executionOrder.add(folder);
                if (progress != null) {
                    progress.onProgress("WEB_SEARCH", 1, 2, 4, folder);
                }
                if ("AlbumA".equals(folder)) {
                    firstStarted.countDown();
                    try { releaseFirst.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return validResponse(folder, false);
            }
        };

        OnlineIdentificationService online = new OnlineIdentificationService(cfg, reviewQueue, stubTagWriter(cfg), null, null, client);
        try (OnlineSearchQueueService sq = new OnlineSearchQueueService(cfg, reviewQueue, online)) {
            // 1. Start AlbumA (occupies single worker)
            sq.enqueueReviewItem(item1.getId(), false);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            // 2. Queue auto AlbumB first, then manual AlbumC -> manual AlbumC must jump ahead of auto AlbumB!
            sq.enqueueMissingTracksAuto(item2);
            sq.enqueueReviewItem(item3.getId(), false);

            @SuppressWarnings("unchecked")
            Map<String, Object> snap = sq.getSnapshot();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> running = (List<Map<String, Object>>) snap.get("running");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> queued = (List<Map<String, Object>>) snap.get("queued");

            assertEquals(1, running.size());
            assertEquals("AlbumA", running.get(0).get("folderName"));
            assertEquals("ROUND1_WEB_SEARCH", running.get(0).get("stageCode"));
            assertEquals(4, ((Number) running.get(0).get("citationsCount")).intValue());

            assertEquals(2, queued.size());
            assertEquals("AlbumC", queued.get(0).get("folderName"));
            assertEquals(1, ((Number) queued.get(0).get("queuePosition")).intValue());
            assertEquals("AlbumB", queued.get(1).get("folderName"));
            assertEquals(2, ((Number) queued.get(1).get("queuePosition")).intValue());

            // 3. Release AlbumA and wait for all 3 to complete in order A -> C -> B
            releaseFirst.countDown();
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                @SuppressWarnings("unchecked")
                Map<String, Object> counts = (Map<String, Object>) sq.getSnapshot().get("counts");
                if (((Number) counts.get("succeeded")).intValue() == 3) break;
                Thread.sleep(25);
            }
            assertEquals(List.of("AlbumA", "AlbumC", "AlbumB"), executionOrder);
        }
    }

    @Test
    void prioritizeCancelAndAbortPreservingRound1WorkCleanly() throws Exception {
        MusicConfig cfg = createConfig(1);
        ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
        ReviewItem item1 = enqueueDummyReview(reviewQueue, "SlowRound2");
        ReviewItem item2 = enqueueDummyReview(reviewQueue, "QueuedFirst");
        ReviewItem item3 = enqueueDummyReview(reviewQueue, "QueuedSecond");

        CountDownLatch enteredRound2 = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);

        WebSearchClient client = new WebSearchClient() {
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override public String name() { return "native"; }
            @Override public SearchResponse search(String sys, String usr) throws LlmClient.LlmException {
                if (usr.contains("SlowRound2")) {
                    int n = callCount.incrementAndGet();
                    if (n == 1) {
                        // Round 1 returns valid candidate AND asks for round 2
                        return validResponse("Round1Winner", true);
                    }
                    // Round 2 blocks until interrupted by cancel()
                    enteredRound2.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new LlmClient.LlmException("interrupted", e);
                    }
                }
                return validResponse("Other", false);
            }
        };

        OnlineIdentificationService online = new OnlineIdentificationService(cfg, reviewQueue, stubTagWriter(cfg), null, null, client);
        try (OnlineSearchQueueService sq = new OnlineSearchQueueService(cfg, reviewQueue, online)) {
            var r1 = sq.enqueueReviewItem(item1.getId(), false);
            assertTrue(enteredRound2.await(5, TimeUnit.SECONDS));

            var q1 = sq.enqueueReviewItem(item2.getId(), false);
            var q2 = sq.enqueueReviewItem(item3.getId(), false);

            // Prioritize q2 ahead of q1
            String q2Id = String.valueOf(q2.task().get("id"));
            String q1Id = String.valueOf(q1.task().get("id"));
            sq.prioritize(q2Id);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> queued = (List<Map<String, Object>>) sq.getSnapshot().get("queued");
            assertEquals(q2Id, queued.get(0).get("id"));

            // Remove q1 from queue
            assertTrue(sq.cancel(q1Id));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> afterRemove = (List<Map<String, Object>>) sq.getSnapshot().get("queued");
            assertEquals(1, afterRemove.size());
            assertEquals(q2Id, afterRemove.get(0).get("id"));

            // Abort running item1 during round 2 -> must preserve round 1 candidates and mark CANCELLED!
            String r1Id = String.valueOf(r1.task().get("id"));
            assertTrue(sq.cancel(r1Id));

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                @SuppressWarnings("unchecked")
                Map<String, Object> counts = (Map<String, Object>) sq.getSnapshot().get("counts");
                if (((Number) counts.get("totalActive")).intValue() == 0
                    && ((Number) counts.get("succeeded")).intValue() == 1
                    && ((Number) counts.get("cancelled")).intValue() == 1) {
                    break;
                }
                Thread.sleep(25);
            }

            ReviewItem refreshed = reviewQueue.get(item1.getId());
            assertEquals(1, refreshed.getOnlineCandidates().size());
            assertEquals("Round1Winner", refreshed.getOnlineCandidates().get(0).getTitle());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> finished = (List<Map<String, Object>>) sq.getSnapshot().get("finished");
            Map<String, Object> cancelledTask = finished.stream()
                .filter(m -> r1Id.equals(m.get("id"))).findFirst().orElseThrow();
            assertEquals("CANCELLED", cancelledTask.get("status"));
            assertEquals(true, cancelledTask.get("round1Saved"));
        }
    }

    @Test
    void failedTaskCanBeRetriedSuccessfully() throws Exception {
        MusicConfig cfg = createConfig(1);
        ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
        ReviewItem item = enqueueDummyReview(reviewQueue, "FlakyAlbum");
        AtomicInteger attempts = new AtomicInteger(0);

        WebSearchClient client = new WebSearchClient() {
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override public String name() { return "tavily"; }
            @Override public SearchResponse search(String sys, String usr) throws LlmClient.LlmException {
                if (attempts.incrementAndGet() == 1) {
                    throw new LlmClient.LlmException("http 429 rate limit");
                }
                return validResponse("RecoveredAlbum", false);
            }
        };

        OnlineIdentificationService online = new OnlineIdentificationService(cfg, reviewQueue, stubTagWriter(cfg), null, null, client);
        try (OnlineSearchQueueService sq = new OnlineSearchQueueService(cfg, reviewQueue, online)) {
            var enq = sq.enqueueReviewItem(item.getId(), false);
            String firstId = String.valueOf(enq.task().get("id"));

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                @SuppressWarnings("unchecked")
                Map<String, Object> counts = (Map<String, Object>) sq.getSnapshot().get("counts");
                if (((Number) counts.get("failed")).intValue() == 1) break;
                Thread.sleep(25);
            }

            sq.retry(firstId);
            deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                @SuppressWarnings("unchecked")
                Map<String, Object> counts = (Map<String, Object>) sq.getSnapshot().get("counts");
                if (((Number) counts.get("succeeded")).intValue() == 1) break;
                Thread.sleep(25);
            }

            ReviewItem refreshed = reviewQueue.get(item.getId());
            assertEquals(1, refreshed.getOnlineCandidates().size());
            assertEquals("RecoveredAlbum", refreshed.getOnlineCandidates().get(0).getTitle());
        }
    }
}
