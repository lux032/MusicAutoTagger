package org.example.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Banner 工具类 - 用于在控制台启动时显示 ASCII Art
 */
public class BannerUtil {

    /**
     * 显示应用启动 Banner
     */
    public static void printBanner() {
        // 先打印ASCII艺术
        printAsciiArt();

        String banner =
            "\n" +
            " ════════════════════════════════════════════════════════════════════════════\n" +
            "  Music Auto Tagger - Automatic Music Metadata Recognition System\n" +
            "  Version: 1.0.0 | Powered by AcoustID & MusicBrainz\n" +
            " ════════════════════════════════════════════════════════════════════════════\n";

        System.out.println(banner);
    }

    /**
     * 从文件读取并打印ASCII艺术
     */
    private static void printAsciiArt() {
        try (InputStream is = BannerUtil.class.getResourceAsStream("/static/banner.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            if (is == null) {
                return;
            }

            System.out.println();
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

        } catch (Exception e) {
            // 如果文件加载失败，静默处理，不影响程序启动
        }
    }
    
    /**
     * 显示简化版 Banner（适用于控制台宽度受限的情况）
     */
    public static void printSimpleBanner() {
        String banner = 
            "\n" +
            "╔══════════════════════════════════════════════════╗\n" +
            "║                                                  ║\n" +
            "║       🎵 Music Auto Tagger System 🎵            ║\n" +
            "║                                                  ║\n" +
            "║   Automatic Music Metadata Recognition          ║\n" +
            "║   Powered by AcoustID & MusicBrainz             ║\n" +
            "║                                                  ║\n" +
            "╚══════════════════════════════════════════════════╝\n";
        
        System.out.println(banner);
    }
    
    /**
     * 显示音符图案
     */
    public static void printMusicNote() {
        String note = 
            "\n" +
            "          ♪♫♪             \n" +
            "        ♪     ♫           \n" +
            "      ♫         ♪         \n" +
            "    ♪             ♫       \n" +
            "  ♫                 ♪     \n" +
            "    ♪             ♫       \n" +
            "      ♫         ♪         \n" +
            "        ♪     ♫           \n" +
            "          ♫♪♫             \n";
        
        System.out.println(note);
    }
}