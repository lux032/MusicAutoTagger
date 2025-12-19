package org.example.model;

/**
 * 音频文件处理结果枚举
 * 用于区分不同类型的处理结果，以便重试逻辑能够正确处理
 */
public enum ProcessResult {
    
    /**
     * 处理成功
     * 文件已成功处理，无需任何后续操作
     */
    SUCCESS,
    
    /**
     * 需要延迟重试（不消耗重试次数）
     * 例如：检测到临时文件，专辑正在下载中
     * 这种情况下文件并非处理失败，只是暂时不适合处理
     * 应该延迟后重试，但不增加重试计数
     */
    DELAY_RETRY,
    
    /**
     * 网络错误需要重试（消耗重试次数）
     * 例如：API 请求超时、连接失败等
     * 这种情况下需要重试，并增加重试计数
     */
    NETWORK_ERROR_RETRY,
    
    /**
     * 永久失败（不重试）
     * 例如：文件无法识别、数据库中无匹配记录等
     * 这种情况下不应重试，直接记录失败
     */
    PERMANENT_FAIL;
    
    /**
     * 检查是否需要重试
     */
    public boolean shouldRetry() {
        return this == DELAY_RETRY || this == NETWORK_ERROR_RETRY;
    }
    
    /**
     * 检查是否应该增加重试计数
     * 只有网络错误等"真正的失败"才应该增加计数
     * 临时文件检测等"延迟重试"不应该增加计数
     */
    public boolean shouldIncrementRetryCount() {
        return this == NETWORK_ERROR_RETRY;
    }
    
    /**
     * 检查是否成功
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
    
    /**
     * 检查是否是永久失败
     */
    public boolean isPermanentFail() {
        return this == PERMANENT_FAIL;
    }
}