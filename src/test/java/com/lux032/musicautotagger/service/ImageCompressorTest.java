package com.lux032.musicautotagger.service;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class ImageCompressorTest {
    byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png", out);
        return out.toByteArray();
    }
    BufferedImage decode(byte[] input) throws Exception {
        byte[] output = ImageCompressor.compressImage(input);
        assertNotNull(output); assertTrue(output.length <= 2 * 1024 * 1024);
        assertEquals(0xff, output[0] & 255); assertEquals(0xd8, output[1] & 255);
        return ImageIO.read(new ByteArrayInputStream(output));
    }
    @Test void smallBytesCannotBypassPixelLimit() throws Exception {
        byte[] input = png(1, 1);
        ByteBuffer.wrap(input).putInt(16, 30000).putInt(20, 30000);
        CRC32 crc = new CRC32(); crc.update(input, 12, 17);
        ByteBuffer.wrap(input).putInt(29, (int) crc.getValue());
        assertTrue(input.length < 1024);
        assertNull(ImageCompressor.compressImage(input));
    }
    @Test void extreme16BitRgbaSourceSidesAreRejectedBeforeRasterDecode() throws Exception {
        for (int[] dimensions : new int[][]{{50_000_000, 1}, {1, 50_000_000}}) {
            byte[] input = png(1, 1);
            ByteBuffer.wrap(input).putInt(16, dimensions[0]).putInt(20, dimensions[1]);
            input[24] = 16; // IHDR bit depth
            input[25] = 6;  // IHDR color type: RGBA
            CRC32 crc = new CRC32(); crc.update(input, 12, 17);
            ByteBuffer.wrap(input).putInt(29, (int) crc.getValue());
            assertTrue(input.length < 1024);
            assertEquals(ImageCompressor.MAX_PIXELS, (long) dimensions[0] * dimensions[1]);
            // Validate that ImageIO accepts this header, without reading its deliberately tiny IDAT.
            try (var stream = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                var reader = ImageIO.getImageReaders(stream).next();
                try {
                    reader.setInput(stream, true, true);
                    assertEquals(dimensions[0], reader.getWidth(0));
                    assertEquals(dimensions[1], reader.getHeight(0));
                } finally { reader.dispose(); }
            }
            assertNull(ImageCompressor.compressImage(input));
        }
    }
    @Test void finiteLargeRasterIsSubsampledDespiteSmallEncodedBytes() throws Exception {
        BufferedImage image = decode(png(6000, 6000));
        assertTrue(image.getWidth() <= 1200); assertTrue(image.getHeight() <= 1200);
    }
    @Test void baselineJpegSubsamplingMeetsBothBudgets() throws Exception {
        BufferedImage source = new BufferedImage(6000, 6000, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(source, "jpeg", out);
        BufferedImage result = decode(out.toByteArray());
        assertTrue(result.getWidth() <= 1200); assertTrue(result.getHeight() <= 1200);
    }
    @Test void byteBudgetRejectsBeforeImageParsing() {
        assertNull(ImageCompressor.compressImage(new byte[ImageCompressor.MAX_INPUT_BYTES + 1]));
    }
    @Test void extremeAspectRatioNeverRoundsToZero() throws Exception {
        BufferedImage image = decode(png(20000, 1));
        assertEquals(1, image.getHeight()); assertTrue(image.getWidth() <= 1200);
    }
    @Test void smallAndTransparentImagesProduceSafeJpeg() throws Exception {
        BufferedImage transparent = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(transparent, "png", output);
        BufferedImage image = decode(output.toByteArray());
        assertEquals(10, image.getWidth()); assertEquals(0xffffff, image.getRGB(0, 0) & 0xffffff);
    }
    @Test void corruptUnknownAndEmptyAreRejected() {
        assertNull(ImageCompressor.compressImage(new byte[]{1, 2, 3}));
        assertNull(ImageCompressor.compressImage("RIFFxxxxWEBP".getBytes()));
        assertNull(ImageCompressor.compressImage(new byte[0]));
        assertNull(ImageCompressor.compressImage(null));
    }
}
