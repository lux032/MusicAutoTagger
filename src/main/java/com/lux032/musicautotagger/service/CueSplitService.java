package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.FileSystemUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class CueSplitService {

    private static final Set<String> SUPPORTED_CUE_AUDIO_EXTS = new HashSet<>(
        Arrays.asList("wav", "flac", "tta", "ape"));
    private static final List<String> COVER_NAME_CANDIDATES = Arrays.asList("cover", "folder", "front");
    private static final Set<String> COVER_EXTENSIONS = new HashSet<>(
        Arrays.asList("jpg", "jpeg", "png", "bmp", "gif", "webp", "tif", "tiff"));
    private static final String DONE_MARKER = ".cue-split.done";
    private static final Map<String, Object> SPLIT_LOCKS = new ConcurrentHashMap<>();

    private final MusicConfig config;
    private final FileSystemUtils fileSystemUtils;

    public CueSplitService(MusicConfig config, FileSystemUtils fileSystemUtils) {
        this.config = config;
        this.fileSystemUtils = fileSystemUtils;
    }

    public SplitResult trySplit(File audioFile) {
        if (audioFile == null || !audioFile.isFile()) {
            return SplitResult.notApplicable();
        }
        if (!config.isCueSplitEnabled()) {
            return SplitResult.notApplicable();
        }
        String outputRoot = config.getCueSplitOutputDir();
        if (outputRoot == null || outputRoot.trim().isEmpty()) {
            log.warn("CUE split enabled but output directory is not configured.");
            return SplitResult.notApplicable();
        }
        if (isUnderOutputDir(audioFile, outputRoot)) {
            return SplitResult.notApplicable();
        }

        File albumRootDir = fileSystemUtils.getAlbumRootDirectory(audioFile);
        if (albumRootDir == null || !albumRootDir.isDirectory()) {
            return SplitResult.notApplicable();
        }

        File[] cueFiles = listCueFiles(albumRootDir);
        if (cueFiles.length != 1) {
            return SplitResult.notApplicable();
        }
        File cueFile = cueFiles[0];

        File[] audioFiles = listAudioFiles(albumRootDir);
        if (audioFiles.length != 1) {
            return SplitResult.notApplicable();
        }
        File bigAudioFile = audioFiles[0];
        String ext = getExtension(bigAudioFile.getName());
        if (!SUPPORTED_CUE_AUDIO_EXTS.contains(ext)) {
            return SplitResult.notApplicable();
        }

        CueAlbumInfo cueInfo;
        try {
            cueInfo = parseCue(cueFile);
        } catch (IOException e) {
            log.warn("Failed to parse cue file: {} - {}", cueFile.getName(), e.getMessage());
            return SplitResult.notApplicable();
        }

        if (cueInfo == null || cueInfo.getTracks().isEmpty()) {
            return SplitResult.notApplicable();
        }
        if (cueInfo.getReferencedFileName() == null ||
            !cueInfo.getReferencedFileName().equalsIgnoreCase(bigAudioFile.getName())) {
            return SplitResult.notApplicable();
        }

        String lockKey = getCanonicalPath(albumRootDir);
        Object lock = SPLIT_LOCKS.computeIfAbsent(lockKey, k -> new Object());
        synchronized (lock) {
            File outputDir = prepareOutputDir(outputRoot, albumRootDir, cueFile);
            if (outputDir == null) {
                return SplitResult.notApplicable();
            }

            File doneMarker = new File(outputDir, DONE_MARKER);
            List<File> existingSplitFiles = listSplitFiles(outputDir);
            if (doneMarker.exists()) {
                if (!existingSplitFiles.isEmpty()) {
                    return SplitResult.existing(existingSplitFiles, cueInfo, outputDir);
                }
                outputDir = new File(outputRoot,
                    albumRootDir.getName() + "__" + System.currentTimeMillis());
                doneMarker = new File(outputDir, DONE_MARKER);
            }

            if (!outputDir.exists() && !outputDir.mkdirs()) {
                log.warn("Failed to create cue split output directory: {}", outputDir.getAbsolutePath());
                return SplitResult.notApplicable();
            }

            if (cueInfo.getAlbumTitle() == null || cueInfo.getAlbumTitle().isEmpty()) {
                cueInfo.setAlbumTitle(albumRootDir.getName());
            }

            try {
                List<File> splitFiles = splitWithFfmpeg(bigAudioFile, cueInfo, outputDir);
                if (splitFiles.isEmpty()) {
                    return SplitResult.notApplicable();
                }
                copyCoverArt(albumRootDir, outputDir);
                writeDoneMarker(doneMarker, cueFile, bigAudioFile);
                return SplitResult.performed(splitFiles, cueInfo, outputDir);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Cue split interrupted for {}: {}", bigAudioFile.getName(), e.getMessage());
                return SplitResult.notApplicable();
            } catch (IOException e) {
                log.error("Cue split failed for {}: {}", bigAudioFile.getName(), e.getMessage());
                return SplitResult.notApplicable();
            }
        }
    }

    private File[] listCueFiles(File albumRootDir) {
        File[] files = albumRootDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".cue"));
        return files != null ? files : new File[0];
    }

    private File[] listAudioFiles(File albumRootDir) {
        File[] files = albumRootDir.listFiles(file -> file.isFile() && fileSystemUtils.isMusicFile(file));
        return files != null ? files : new File[0];
    }

    private List<File> listSplitFiles(File outputDir) {
        File[] files = outputDir.listFiles(file -> file.isFile() && "flac".equals(getExtension(file.getName())));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return sorted;
    }

    private File prepareOutputDir(String outputRoot, File albumRootDir, File cueFile) {
        File baseDir = new File(outputRoot);
        File outputDir = new File(baseDir, albumRootDir.getName());
        if (!outputDir.exists()) {
            return outputDir;
        }

        File doneMarker = new File(outputDir, DONE_MARKER);
        if (doneMarker.exists()) {
            return outputDir;
        }

        String suffix = Integer.toHexString(
            Math.abs((albumRootDir.getAbsolutePath() + cueFile.lastModified()).hashCode()));
        File fallbackDir = new File(baseDir, albumRootDir.getName() + "__" + suffix);
        if (!fallbackDir.exists()) {
            return fallbackDir;
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        return new File(baseDir, albumRootDir.getName() + "__" + timestamp);
    }

    private void copyCoverArt(File albumRootDir, File outputDir) {
        File[] files = albumRootDir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return;
        }

        Map<String, File> candidates = new HashMap<>();
        for (File file : files) {
            String name = file.getName();
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex <= 0) {
                continue;
            }
            String base = name.substring(0, dotIndex).toLowerCase(Locale.ROOT);
            String ext = name.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
            if (COVER_EXTENSIONS.contains(ext) && COVER_NAME_CANDIDATES.contains(base)) {
                candidates.put(base, file);
            }
        }

        for (String base : COVER_NAME_CANDIDATES) {
            File source = candidates.get(base);
            if (source == null) {
                continue;
            }
            String ext = getExtension(source.getName());
            if (ext.isEmpty()) {
                continue;
            }
            File target = new File(outputDir, "cover." + ext);
            try {
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied cover art to split directory: {}", target.getName());
            } catch (IOException e) {
                log.warn("Failed to copy cover art: {}", e.getMessage());
            }
            break;
        }
    }

    private void writeDoneMarker(File doneMarker, File cueFile, File audioFile) {
        try {
            String content = "cue=" + cueFile.getName() + System.lineSeparator() +
                "audio=" + audioFile.getName() + System.lineSeparator();
            Files.writeString(doneMarker.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to write cue split marker: {}", e.getMessage());
        }
    }

    private List<File> splitWithFfmpeg(File inputFile, CueAlbumInfo cueInfo, File outputDir)
        throws IOException, InterruptedException {

        List<CueTrack> tracks = cueInfo.getTracks();
        if (tracks.isEmpty()) {
            return Collections.emptyList();
        }

        int trackCount = tracks.size();
        int width = Math.max(2, String.valueOf(trackCount).length());

        List<File> outputFiles = new ArrayList<>();
        String ffmpegPath = config.getAudioNormalizeFfmpegPath();
        if (ffmpegPath == null || ffmpegPath.isEmpty()) {
            ffmpegPath = "ffmpeg";
        }

        for (int i = 0; i < tracks.size(); i++) {
            CueTrack track = tracks.get(i);
            String trackTitle = track.getTitle();
            if (trackTitle == null || trackTitle.trim().isEmpty()) {
                trackTitle = "Track " + track.getNumber();
            }

            String fileName = String.format(Locale.ROOT, "%0" + width + "d - %s.flac",
                track.getNumber(), sanitizeFileName(trackTitle));
            File outputFile = new File(outputDir, fileName);

            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-y");
            command.add("-i");
            command.add(inputFile.getAbsolutePath());
            command.add("-ss");
            command.add(formatSeconds(track.getStartSeconds()));
            if (track.getEndSeconds() != null) {
                command.add("-to");
                command.add(formatSeconds(track.getEndSeconds()));
            }
            command.add("-c:a");
            command.add("flac");
            addMetadata(command, "title", trackTitle);
            addMetadata(command, "album", cueInfo.getAlbumTitle());
            addMetadata(command, "artist", pickArtist(track, cueInfo));
            addMetadata(command, "album_artist", cueInfo.getAlbumArtist());
            addMetadata(command, "tracknumber", String.valueOf(track.getNumber()));
            addMetadata(command, "tracktotal", String.valueOf(trackCount));
            if (cueInfo.getDiscNumber() != null) {
                addMetadata(command, "discnumber", cueInfo.getDiscNumber());
            }
            if (cueInfo.getDate() != null) {
                addMetadata(command, "date", cueInfo.getDate());
            }
            if (cueInfo.getGenre() != null) {
                addMetadata(command, "genre", cueInfo.getGenre());
            }
            command.add(outputFile.getAbsolutePath());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                if (outputFile.exists()) {
                    Files.deleteIfExists(outputFile.toPath());
                }
                throw new IOException("ffmpeg failed: " + output.trim());
            }

            outputFiles.add(outputFile);
        }

        return outputFiles;
    }

    private String pickArtist(CueTrack track, CueAlbumInfo cueInfo) {
        if (track.getArtist() != null && !track.getArtist().trim().isEmpty()) {
            return track.getArtist();
        }
        if (cueInfo.getAlbumArtist() != null && !cueInfo.getAlbumArtist().trim().isEmpty()) {
            return cueInfo.getAlbumArtist();
        }
        return null;
    }

    private void addMetadata(List<String> command, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        command.add("-metadata");
        command.add(key + "=" + value.trim());
    }

    private CueAlbumInfo parseCue(File cueFile) throws IOException {
        CueAlbumInfo albumInfo = new CueAlbumInfo();
        CueTrack currentTrack = null;

        List<String> lines = readCueLines(cueFile.toPath());
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("FILE ")) {
                String fileName = extractQuotedValue(line);
                if (fileName != null && !fileName.isEmpty()) {
                    String[] tokens = fileName.split("\\s+");
                    fileName = tokens[0];
                }
                if (fileName == null || fileName.isEmpty()) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        fileName = parts[1];
                    }
                }
                albumInfo.setReferencedFileName(stripQuotes(fileName));
            } else if (upper.startsWith("TRACK ")) {
                int number = parseTrackNumber(line);
                currentTrack = new CueTrack(number);
                albumInfo.getTracks().add(currentTrack);
            } else if (upper.startsWith("TITLE ")) {
                String title = extractQuotedValue(line);
                if (currentTrack != null) {
                    currentTrack.setTitle(title);
                } else {
                    albumInfo.setAlbumTitle(title);
                }
            } else if (upper.startsWith("PERFORMER ")) {
                String performer = extractQuotedValue(line);
                if (currentTrack != null) {
                    currentTrack.setArtist(performer);
                } else {
                    albumInfo.setAlbumArtist(performer);
                }
            } else if (upper.startsWith("INDEX 01")) {
                if (currentTrack != null) {
                    String timeStr = line.substring(8).trim();
                    currentTrack.setStartSeconds(parseCueTimeToSeconds(timeStr));
                }
            } else if (upper.startsWith("REM GENRE")) {
                albumInfo.setGenre(line.substring(9).trim());
            } else if (upper.startsWith("REM DATE")) {
                albumInfo.setDate(line.substring(8).trim());
            } else if (upper.startsWith("REM YEAR")) {
                albumInfo.setDate(line.substring(8).trim());
            } else if (upper.startsWith("REM DISCNUMBER")) {
                albumInfo.setDiscNumber(line.substring(14).trim());
            } else if (upper.startsWith("REM DISC")) {
                albumInfo.setDiscNumber(line.substring(8).trim());
            }
        }

        List<CueTrack> tracks = albumInfo.getTracks();
        for (int i = 0; i < tracks.size() - 1; i++) {
            CueTrack current = tracks.get(i);
            CueTrack next = tracks.get(i + 1);
            current.setEndSeconds(next.getStartSeconds());
        }

        return albumInfo;
    }

    private List<String> readCueLines(Path cuePath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(cuePath, StandardCharsets.UTF_8)) {
            return reader.lines().toList();
        } catch (IOException e) {
            try (BufferedReader reader = Files.newBufferedReader(cuePath, Charset.defaultCharset())) {
                return reader.lines().toList();
            }
        }
    }

    private String extractQuotedValue(String line) {
        int firstQuote = line.indexOf('"');
        int lastQuote = line.lastIndexOf('"');
        if (firstQuote >= 0 && lastQuote > firstQuote) {
            return line.substring(firstQuote + 1, lastQuote);
        }
        String trimmed = line.substring(line.indexOf(' ') + 1).trim();
        return stripQuotes(trimmed);
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private int parseTrackNumber(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private double parseCueTimeToSeconds(String timeStr) {
        String[] parts = timeStr.split(":");
        if (parts.length != 3) {
            return 0;
        }
        try {
            int minutes = Integer.parseInt(parts[0].trim());
            int seconds = Integer.parseInt(parts[1].trim());
            int frames = Integer.parseInt(parts[2].trim());
            return minutes * 60.0 + seconds + frames / 75.0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private String sanitizeFileName(String name) {
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        if (sanitized.isEmpty()) {
            return "Track";
        }
        return sanitized;
    }

    private String getExtension(String name) {
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == name.length() - 1) {
            return "";
        }
        return name.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isUnderOutputDir(File file, String outputRoot) {
        try {
            String filePath = file.getCanonicalPath();
            String outputPath = new File(outputRoot).getCanonicalPath();
            return filePath.startsWith(outputPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private String getCanonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    public static class SplitResult {
        private final boolean performed;
        private final boolean usedExisting;
        private final List<File> splitFiles;
        private final CueAlbumInfo cueInfo;
        private final File outputDir;

        private SplitResult(boolean performed, boolean usedExisting, List<File> splitFiles,
                           CueAlbumInfo cueInfo, File outputDir) {
            this.performed = performed;
            this.usedExisting = usedExisting;
            this.splitFiles = splitFiles;
            this.cueInfo = cueInfo;
            this.outputDir = outputDir;
        }

        public static SplitResult notApplicable() {
            return new SplitResult(false, false, Collections.emptyList(), null, null);
        }

        public static SplitResult performed(List<File> splitFiles, CueAlbumInfo cueInfo, File outputDir) {
            return new SplitResult(true, false, splitFiles, cueInfo, outputDir);
        }

        public static SplitResult existing(List<File> splitFiles, CueAlbumInfo cueInfo, File outputDir) {
            return new SplitResult(true, true, splitFiles, cueInfo, outputDir);
        }

        public boolean isPerformed() {
            return performed;
        }

        public boolean isUsedExisting() {
            return usedExisting;
        }

        public List<File> getSplitFiles() {
            return splitFiles;
        }

        public CueAlbumInfo getCueInfo() {
            return cueInfo;
        }

        public File getOutputDir() {
            return outputDir;
        }
    }

    public static class CueAlbumInfo {
        private String albumTitle;
        private String albumArtist;
        private String genre;
        private String date;
        private String discNumber;
        private String referencedFileName;
        private final List<CueTrack> tracks = new ArrayList<>();

        public String getAlbumTitle() {
            return albumTitle;
        }

        public void setAlbumTitle(String albumTitle) {
            this.albumTitle = albumTitle;
        }

        public String getAlbumArtist() {
            return albumArtist;
        }

        public void setAlbumArtist(String albumArtist) {
            this.albumArtist = albumArtist;
        }

        public String getGenre() {
            return genre;
        }

        public void setGenre(String genre) {
            this.genre = genre;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public String getDiscNumber() {
            return discNumber;
        }

        public void setDiscNumber(String discNumber) {
            this.discNumber = discNumber;
        }

        public String getReferencedFileName() {
            return referencedFileName;
        }

        public void setReferencedFileName(String referencedFileName) {
            this.referencedFileName = referencedFileName;
        }

        public List<CueTrack> getTracks() {
            return tracks;
        }
    }

    public static class CueTrack {
        private final int number;
        private String title;
        private String artist;
        private double startSeconds;
        private Double endSeconds;

        public CueTrack(int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getArtist() {
            return artist;
        }

        public void setArtist(String artist) {
            this.artist = artist;
        }

        public double getStartSeconds() {
            return startSeconds;
        }

        public void setStartSeconds(double startSeconds) {
            this.startSeconds = startSeconds;
        }

        public Double getEndSeconds() {
            return endSeconds;
        }

        public void setEndSeconds(Double endSeconds) {
            this.endSeconds = endSeconds;
        }
    }
}

