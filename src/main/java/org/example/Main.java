package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.core.ApplicationLifecycleManager;
import org.example.util.I18nUtil;

/**
 * 音乐文件自动标签系统主程序
 * 功能：
 * 1. 监控指定目录的音乐文件下载
 * 2. 使用音频指纹识别技术识别音乐
 * 3. 通过 MusicBrainz 获取音乐元数据
 * 4. 自动更新音频文件的标签信息
 */
@Slf4j
public class Main {
    
    private static ApplicationLifecycleManager lifecycleManager;
    
    public static void main(String[] args) {
        System.out.println(I18nUtil.getMessage("main.title.separator"));
        System.out.println(I18nUtil.getMessage("main.title"));
        System.out.println(I18nUtil.getMessage("main.title.separator"));
        
        try {
            // 1. 加载配置
            MusicConfig config = MusicConfig.getInstance();
            if (!config.isValid()) {
                log.error(I18nUtil.getMessage("app.config.invalid"));
                return;
            }
            
            log.info(I18nUtil.getMessage("app.config.loaded"));
            log.info(I18nUtil.getMessage("app.monitor.directory"), config.getMonitorDirectory());
            log.info(I18nUtil.getMessage("app.output.directory"), config.getOutputDirectory());
            log.info(I18nUtil.getMessage("app.scan.interval"), config.getScanIntervalSeconds());
            
            // 2. 创建并初始化生命周期管理器
            lifecycleManager = new ApplicationLifecycleManager(config);
            lifecycleManager.initializeServices();
            
            // 3. 检查依赖工具
            if (!lifecycleManager.isFpcalcAvailable()) {
                log.warn(I18nUtil.getMessage("main.title.separator"));
                log.warn(I18nUtil.getMessage("main.fpcalc.warning.line"));
                log.warn(I18nUtil.getMessage("main.fpcalc.feature.disabled"));
                log.warn(I18nUtil.getMessage("main.fpcalc.install.guide"));
                log.warn(I18nUtil.getMessage("main.title.separator"));
            }
            
            // 4. 启动 Web 监控面板
            lifecycleManager.startWebServer();
            
            // 5. 启动文件监控
            lifecycleManager.startMonitoring();
            
            // 6. 等待用户输入以停止程序
            System.out.println("\n" + I18nUtil.getMessage("main.system.running"));
            System.out.println(I18nUtil.getMessage("main.press.enter.to.stop"));
            System.in.read();
            
            // 7. 优雅关闭
            lifecycleManager.shutdown();
            
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("main.error"), e);
        }
    }
}