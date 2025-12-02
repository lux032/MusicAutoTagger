package org.example.service;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图片压缩服务
 * 用于压缩封面图片,控制嵌入音频文件的图片大小
 */
@Slf4j
public class ImageCompressor {
    
    private static final int MAX_SIZE_BYTES = 2 * 1024 * 1024; // 2MB
    private static final int MAX_DIMENSION = 1200; // 最大宽高
    private static final float INITIAL_QUALITY = 0.85f; // 初始压缩质量
    private static final float MIN_QUALITY = 0.5f; // 最低质量
    private static final float QUALITY_STEP = 0.05f; // 质量递减步长
    
    /**
     * 压缩图片数据,确保不超过指定大小
     * @param imageData 原始图片数据
     * @return 压缩后的图片数据
     */
    public static byte[] compressImage(byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            return imageData;
        }
        
        // 如果图片已经小于2MB,直接返回
        if (imageData.length <= MAX_SIZE_BYTES) {
            log.info("图片大小: {} KB,无需压缩", imageData.length / 1024);
            return imageData;
        }
        
        log.info("原始图片大小: {} KB,开始压缩...", imageData.length / 1024);
        
        try {
            // 读取图片
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageData));
            if (originalImage == null) {
                log.warn("无法读取图片,返回原始数据");
                return imageData;
            }
            
            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();
            log.info("原始图片尺寸: {}x{}", originalWidth, originalHeight);
            
            // 1. 首先尝试缩放图片尺寸
            BufferedImage scaledImage = originalImage;
            if (originalWidth > MAX_DIMENSION || originalHeight > MAX_DIMENSION) {
                scaledImage = scaleImage(originalImage, MAX_DIMENSION);
                log.info("缩放后尺寸: {}x{}", scaledImage.getWidth(), scaledImage.getHeight());
            }
            
            // 2. 调整压缩质量直到满足大小要求
            byte[] compressedData = null;
            float quality = INITIAL_QUALITY;
            
            while (quality >= MIN_QUALITY) {
                compressedData = compressToJPEG(scaledImage, quality);
                
                if (compressedData.length <= MAX_SIZE_BYTES) {
                    log.info("压缩成功! 最终大小: {} KB, 质量: {}%", 
                        compressedData.length / 1024, (int)(quality * 100));
                    return compressedData;
                }
                
                quality -= QUALITY_STEP;
            }
            
            // 如果最低质量仍然太大,进一步缩小尺寸
            log.warn("最低质量仍超过2MB,进一步缩小尺寸");
            int newMaxDimension = MAX_DIMENSION;
            
            while (newMaxDimension > 400 && compressedData.length > MAX_SIZE_BYTES) {
                newMaxDimension -= 100;
                scaledImage = scaleImage(originalImage, newMaxDimension);
                compressedData = compressToJPEG(scaledImage, MIN_QUALITY);
                log.info("尺寸: {}x{}, 大小: {} KB", 
                    scaledImage.getWidth(), scaledImage.getHeight(), 
                    compressedData.length / 1024);
            }
            
            if (compressedData.length <= MAX_SIZE_BYTES) {
                log.info("压缩成功! 最终大小: {} KB", compressedData.length / 1024);
                return compressedData;
            } else {
                log.warn("无法将图片压缩到2MB以内,使用最小化版本");
                return compressedData;
            }
            
        } catch (IOException e) {
            log.error("图片压缩失败,返回原始数据", e);
            return imageData;
        }
    }
    
    /**
     * 缩放图片
     */
    private static BufferedImage scaleImage(BufferedImage original, int maxDimension) {
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();
        
        // 计算缩放比例(保持宽高比)
        double scale;
        if (originalWidth > originalHeight) {
            scale = (double) maxDimension / originalWidth;
        } else {
            scale = (double) maxDimension / originalHeight;
        }
        
        int scaledWidth = (int) (originalWidth * scale);
        int scaledHeight = (int) (originalHeight * scale);
        
        // 创建缩放后的图片
        Image scaledImage = original.getScaledInstance(
            scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
        
        BufferedImage bufferedScaledImage = new BufferedImage(
            scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
        
        Graphics2D g2d = bufferedScaledImage.createGraphics();
        g2d.drawImage(scaledImage, 0, 0, null);
        g2d.dispose();
        
        return bufferedScaledImage;
    }
    
    /**
     * 将图片压缩为JPEG格式
     */
    private static byte[] compressToJPEG(BufferedImage image, float quality) throws IOException {
        // 确保图片是RGB格式(JPEG不支持透明度)
        BufferedImage rgbImage = new BufferedImage(
            image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgbImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        
        // 获取JPEG写入器
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("没有可用的JPEG写入器");
        }
        
        ImageWriter writer = writers.next();
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        
        // 设置压缩质量
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionQuality(quality);
        
        // 写入到字节数组
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgbImage, null, null), writeParam);
        } finally {
            writer.dispose();
        }
        
        return baos.toByteArray();
    }
}