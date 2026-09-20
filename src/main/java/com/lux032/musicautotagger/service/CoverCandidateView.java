package com.lux032.musicautotagger.service;

import lombok.Data;

/** Lightweight cover choice persisted in the review queue; image bytes stay on disk. */
@Data
public class CoverCandidateView {
    public enum Origin { MUSICBRAINZ, FOLDER, EMBEDDED, LLM_URL }

    private String sha256;
    private Origin origin;
    private String originLabel;
    private String sourceUrl;
    private String sourceFileName;
    private int width;
    private int height;
    private long bytes;
    private String mime;
    /** Present only for a verified MusicBrainz candidate and used after confirmation. */
    private String releaseGroupId;
}
