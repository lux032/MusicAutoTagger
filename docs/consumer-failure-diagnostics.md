# 消费者故障与 JVM 诊断

认证后的 `GET /api/control/` 保留 `monitoringRunning` / `monitoringPaused`，并追加队列数、三个长期任务状态和最后故障摘要。`running=true` 不代表健康；`FAULTED` 不自动重启。致命 Error 的当前文件不永久标记 FAILED。处理记录页在消费者不可用时拒绝重新识别，不删除记录。

RuntimeException 按文件隔离；FAILED 持久化失败的文件保留在本进程内存，`unrecordedFailureCount` 可见并有 Web 告警。该保留不是持久化恢复队列，进程退出后应根据完整日志人工核对。stop 后不支持同实例 start；暂停/恢复不创建新消费者。

封面输入硬上限为 64MiB / 50,000,000 像素，源图任一边不得超过 32768px（部分解码器在降采样前仍分配完整源行，像素总量不足以限制此开销）。先读取头尺寸，再降采样和精确缩放，输出 JPEG ≤1200px / ≤2MiB。拒绝当前来源后继续降级，最终无封面 WARN 继续。局部内存 ImageIO 流不会更改全局缓存设置。降采样不保证消除 JVM 或原生内存 OOM。

启动日志打印真实 `Runtime.maxMemory()` 字节值。运维可在**核实容器内存预算与磁盘权限/容量后**选择诊断 JVM 参数，例如 `-Xlog:gc*:file=/configured/writable/path/gc.log:time,uptime,level,tags:filecount=3,filesize=10M`。本次不修改默认堆、Docker 参数、heapdump 或 ExitOnOutOfMemoryError。Heap dump 可能接近 Xmx 大小并包含敏感数据；ExitOnOOM 配合自动重启可能让毒文件反复击杀进程，不应未经评估启用。

生产事故具体 Error 尚未取得直接证据；模拟 `throw new OutOfMemoryError` 只验证 fail-stop 路径，不是生产根因重放，也不是真实内存耗尽试验。
