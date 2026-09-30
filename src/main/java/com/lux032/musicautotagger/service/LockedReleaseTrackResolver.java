package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.util.MetadataUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * 把一首已识别的歌定位到选定的 Release 里。
 *
 * <ul>
 *   <li>能对上（Recording ID / 曲名 + 时长）：使用该版本的碟号、曲目号、曲目标题，保留识别得到的作曲/作词等；</li>
 *   <li>对不上：作为<b>附加曲目</b>——保留指纹识别出的真实曲名，不写曲目号（不编造）；</li>
 *   <li>取不到版本数据：原样返回，不做任何猜测。</li>
 * </ul>
 *
 * 人工在待确认面板选定版本、以及关闭人工确认时的自动兜底，都走这里。
 */
@Slf4j
public class LockedReleaseTrackResolver {

    private final MusicBrainzClient musicBrainzClient;

    public LockedReleaseTrackResolver(MusicBrainzClient musicBrainzClient) {
        this.musicBrainzClient = musicBrainzClient;
    }

    /** 定位结果 */
    public static final class Resolution {
        private final MusicMetadata metadata;
        private final MusicBrainzClient.TrackMatchType matchType;

        Resolution(MusicMetadata metadata, MusicBrainzClient.TrackMatchType matchType) {
            this.metadata = metadata;
            this.matchType = matchType;
        }

        public MusicMetadata getMetadata() { return metadata; }
        public MusicBrainzClient.TrackMatchType getMatchType() { return matchType; }
        public boolean isExtraTrack() { return metadata != null && metadata.isExtraTrack(); }
    }

    /**
     * @param identified  指纹识别得到的曲目元数据（可能为空对象）
     * @param duration    文件时长（秒），未知时为 null
     */
    public Resolution resolve(MusicMetadata identified, Integer duration,
                              String releaseId, String releaseGroupId,
                              String albumTitle, String albumArtist,
                              String releaseType, boolean compilation) {
        MusicMetadata base = identified != null ? identified : new MusicMetadata();
        if (musicBrainzClient == null || releaseId == null || releaseId.isEmpty()) {
            return new Resolution(base, MusicBrainzClient.TrackMatchType.UNAVAILABLE);
        }
        int seconds = duration != null && duration > 0 ? duration : 0;
        MusicBrainzClient.TrackMatch match = musicBrainzClient.matchTrackInRelease(
            releaseId, releaseGroupId, base.getRecordingId(), base.getTitle(), seconds, albumTitle, albumArtist);

        if (match.isFound()) {
            MusicMetadata fromRelease = match.getMetadata();
            // Release 详情不含曲目级创作信息，保留识别结果里的作曲/作词/编曲/歌词/流派
            MetadataUtils.preserveTrackCredits(base, fromRelease);
            fromRelease.setReleaseId(releaseId);
            if (releaseType != null && !releaseType.isEmpty()) {
                fromRelease.setReleaseType(releaseType);
            }
            fromRelease.setCompilation(compilation || fromRelease.isCompilation());
            if (duration != null) {
                fromRelease.setDuration(duration);
            }
            // 伴奏与原唱通常等长：保留源元数据中明确的版本标题与曲序
            return new Resolution(MetadataUtils.mergeMetadata(base, fromRelease), match.getType());
        }

        if (match.isNotInRelease()) {
            log.info("曲目不在所选版本中，作为附加曲目归档: {}", base.getTitle());
            base.setExtraTrack(true);
            base.setDiscNo(null);
            base.setTrackNo(null);
            base.setTrackTotal(null);
            base.setDiscTotal(null);
            base.setReleaseTrackId(null);
            base.setReleaseId(releaseId);
            return new Resolution(base, match.getType());
        }

        return new Resolution(base, match.getType());
    }
}
