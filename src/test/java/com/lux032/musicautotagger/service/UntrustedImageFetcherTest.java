package com.lux032.musicautotagger.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class UntrustedImageFetcherTest {
    @Test
    void inspectsImageWithoutFullDecodeContractLeak() throws Exception {
        BufferedImage image = new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        var result = UntrustedImageFetcher.inspect(out.toByteArray()).orElseThrow();
        assertEquals("image/png", result.mime());
        assertEquals(7, result.width());
        assertEquals(5, result.height());
    }

    @Test
    void rejectsNonImageBytes() {
        assertTrue(UntrustedImageFetcher.inspect(new byte[]{1, 2, 3}).isEmpty());
    }

    @Test
    void rejectsReservedIpv4Ranges() throws Exception {
        String[] blocked = {
            "0.1.2.3", "100.64.0.1", "100.127.255.254", "169.254.1.1",
            "192.0.0.1", "198.18.0.1", "198.19.255.254", "192.88.99.1"
        };
        for (String address : blocked) {
            assertFalse(UntrustedImageFetcher.isPublic(InetAddress.getByName(address)), address);
        }
        assertTrue(UntrustedImageFetcher.isPublic(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void rejectsReservedIpv6RangesAndIpv4CompatibleForm() throws Exception {
        String[] blocked = {"fc00::1", "fd00::1", "64:ff9b::808:808", "::8.8.8.8"};
        for (String address : blocked) {
            assertFalse(UntrustedImageFetcher.isPublic(InetAddress.getByName(address)), address);
        }
        assertTrue(UntrustedImageFetcher.isPublic(InetAddress.getByName("2001:4860:4860::8888")));
    }
}
