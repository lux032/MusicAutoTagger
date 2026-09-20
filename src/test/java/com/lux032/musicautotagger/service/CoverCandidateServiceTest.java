package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverCandidateServiceTest {
    @TempDir Path temp;

    @Test
    void rejectsShaThatIsNotInTheAllowedCandidateList() throws Exception {
        MusicConfig config = MusicConfig.getInstance();
        String oldCache = config.getCoverArtCacheDirectory();
        config.setCoverArtCacheDirectory(temp.toString());
        try {
            String requestedSha = "a".repeat(64);
            Files.createDirectories(CoverCandidateCache.directory(config));
            Files.write(CoverCandidateCache.directory(config).resolve(requestedSha + ".jpg"), new byte[]{1});

            CoverCandidateView other = new CoverCandidateView();
            other.setSha256("b".repeat(64));
            try (CoverCandidateService service = new CoverCandidateService(config, null, null, null)) {
                assertTrue(service.resolveBySha256(requestedSha, List.of(other)).isEmpty());
            }
        } finally {
            config.setCoverArtCacheDirectory(oldCache);
        }
    }
}
