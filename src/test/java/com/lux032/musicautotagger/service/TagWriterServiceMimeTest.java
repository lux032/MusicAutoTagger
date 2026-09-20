package com.lux032.musicautotagger.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TagWriterServiceMimeTest {
    @Test
    void detectsPngAndJpegFromImageBytes() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        assertEquals("image/png", TagWriterService.detectMimeType(write(image, "png")));
        assertEquals("image/jpeg", TagWriterService.detectMimeType(write(image, "jpg")));
    }

    @Test
    void fallsBackToJpegForUnknownBytes() {
        assertEquals("image/jpeg", TagWriterService.detectMimeType(new byte[]{1, 2, 3}));
    }

    private byte[] write(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
