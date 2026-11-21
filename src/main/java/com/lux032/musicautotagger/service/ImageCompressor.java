package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.util.ProcessRunner;
import lombok.extern.slf4j.Slf4j;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Bounded cover decoding. Subsampling reduces risk; it cannot eliminate all JVM/native OOMs. */
@Slf4j
public class ImageCompressor {
    public static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;
    static final long MAX_PIXELS = 50_000_000L;
    // Some readers allocate full source rows before subsampling; pixel count alone is insufficient.
    static final int MAX_SOURCE_DIMENSION = 32768;
    private static final int MAX_SIZE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 1200;

    /** Returns null when this source is unsafe or cannot be decoded; never returns unsafe original bytes. */
    public static byte[] compressImage(byte[] data) {
        return compressImage(data, null);
    }

    /**
     * Bounded decode + JPEG compression (<=1200px, <=2MiB).
     * When ImageIO cannot decode a format (e.g. WebP or arithmetic-coded JPEG) and {@code ffmpegPath} is provided,
     * converts a single frame to JPEG via ffmpeg and runs the same bounded compression on the converted bytes.
     */
    public static byte[] compressImage(byte[] data, String ffmpegPath) {
        if (data == null || data.length == 0 || data.length > MAX_INPUT_BYTES) {
            log.warn("Cover input rejected (empty or over 64MiB)");
            return null;
        }
        DecodeOutcome outcome = compressImageDirect(data);
        if (outcome.bytes != null) {
            return outcome.bytes;
        }
        if (outcome.allowFfmpegFallback && ffmpegPath != null && !ffmpegPath.isBlank()) {
            byte[] converted = transcodeToJpegWithFfmpeg(data, ffmpegPath);
            if (converted != null && converted.length > 0 && converted.length <= MAX_INPUT_BYTES) {
                return compressImageDirect(converted).bytes;
            }
        }
        return null;
    }

    private record DecodeOutcome(byte[] bytes, boolean allowFfmpegFallback) {}

    private static DecodeOutcome compressImageDirect(byte[] data) {
        ImageReader reader = null;
        long start = System.nanoTime();
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                log.warn("Unknown cover image format");
                return new DecodeOutcome(null, true);
            }
            reader = readers.next();
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width <= 0 || height <= 0 || Math.max(width, height) > MAX_SOURCE_DIMENSION
                || (long) width * height > MAX_PIXELS) {
                log.warn("Cover dimensions rejected: {}x{}", width, height);
                return new DecodeOutcome(null, false);
            }
            int subsample = Math.max(1, (int) Math.ceil((double) Math.max(width, height) / MAX_DIMENSION));
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(subsample, subsample, 0, 0);
            BufferedImage decoded = reader.read(0, param);
            if (decoded == null) return new DecodeOutcome(null, true);
            // Always validate/decode even small inputs; a highly compressed raster may be enormous.
            BufferedImage rgb = scaleImage(decoded, MAX_DIMENSION);
            for (int dimension = MAX_DIMENSION; dimension >= 100; dimension -= 100) {
                if (dimension != MAX_DIMENSION) rgb = scaleImage(rgb, dimension);
                for (int quality = 85; quality >= 50; quality -= 5) {
                    byte[] output = jpeg(rgb, quality / 100f);
                    if (output.length <= MAX_SIZE_BYTES) {
                        log.info("Cover bytes={} dimensions={}x{} format={} subsample={} output={} elapsedMs={}",
                            data.length, width, height, reader.getFormatName(), subsample, output.length,
                            (System.nanoTime() - start) / 1_000_000);
                        return new DecodeOutcome(output, false);
                    }
                }
            }
            log.warn("Cover cannot fit 2MiB budget");
            return new DecodeOutcome(null, false);
        } catch (IOException | RuntimeException e) {
            log.warn("Cover decode rejected: {}", e.getMessage());
            return new DecodeOutcome(null, true);
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    private static byte[] transcodeToJpegWithFfmpeg(byte[] data, String ffmpegPath) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("cover-compress-");
            Path input = dir.resolve("input.image");
            Path output = dir.resolve("output.jpg");
            Files.write(input, data);
            ProcessRunner.Result result = ProcessRunner.execute(List.of(
                ffmpegPath, "-y", "-i", input.toString(), "-frames:v", "1",
                "-vf", "scale='min(" + MAX_DIMENSION + ",iw)':'min(" + MAX_DIMENSION + ",ih)':force_original_aspect_ratio=decrease",
                output.toString()), 20);
            if (!result.isSuccess() || !Files.isRegularFile(output)) {
                return null;
            }
            return Files.readAllBytes(output);
        } catch (Exception e) {
            log.debug("FFmpeg cover fallback failed: {}", e.getMessage());
            return null;
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static BufferedImage scaleImage(BufferedImage image, int maximum) {
        double scale = Math.min(1d, (double) maximum / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.floor(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.floor(image.getHeight() * scale));
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return rgb;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG writer");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }
}
