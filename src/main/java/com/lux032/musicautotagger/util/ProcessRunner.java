package com.lux032.musicautotagger.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 外部子进程执行工具(fpcalc / ffmpeg 等)
 *
 * 统一处理两个容易踩坑的地方:
 *
 * 1. <b>输出必须被消费</b> —— 子进程写满管道缓冲区(Linux 上通常 64KB)后会永久阻塞。
 *    这里合并 stderr 到 stdout 并在独立线程中持续读取。
 *
 * 2. <b>必须有硬超时</b> —— 整条文件处理流水线只有一个消费者线程,
 *    单个子进程挂起等于全站停摆,而且进程还活着,外部健康检查发现不了。
 *    读取放在独立线程正是为了让超时生效: 如果在当前线程读,
 *    read() 自身就会一直阻塞,waitFor 的超时永远轮不到执行。
 */
@Slf4j
public final class ProcessRunner {

    /** 子进程退出后,等待输出线程读完剩余内容的时间 */
    private static final long READER_DRAIN_TIMEOUT_MS = 5000;

    private static final AtomicLong READER_THREAD_SEQ = new AtomicLong();

    private ProcessRunner() {
    }

    /**
     * 执行子进程,返回其合并后的输出(stdout + stderr)
     *
     * @param command        完整命令行
     * @param timeoutSeconds 超时秒数,超时将强制终止子进程并抛出 IOException
     * @return 子进程输出
     * @throws IOException          启动失败、超时,或退出码非 0
     * @throws InterruptedException 当前线程在等待子进程时被中断
     */
    public static String run(List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        Result result = execute(command, timeoutSeconds);
        if (result.exitCode != 0) {
            throw new IOException(describe(command) + " 执行失败，退出码: " + result.exitCode
                + (result.output.isBlank() ? "" : " - " + result.output.trim()));
        }
        return result.output;
    }

    /**
     * 执行子进程并返回退出码与输出,不对非 0 退出码抛异常
     * 适用于调用方需要自行区分退出码的场景
     *
     * @throws IOException 启动失败或超时
     */
    public static Result execute(List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        Thread readerThread = new Thread(
            () -> drain(process, output),
            "proc-reader-" + READER_THREAD_SEQ.incrementAndGet());
        readerThread.setDaemon(true);
        readerThread.start();

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException(describe(command) + " 执行超时(" + timeoutSeconds + "秒)");
            }

            // 进程已退出,给输出线程一点时间读完管道里剩下的内容
            readerThread.join(READER_DRAIN_TIMEOUT_MS);

            synchronized (output) {
                return new Result(process.exitValue(), output.toString());
            }
        } finally {
            if (process.isAlive()) {
                log.warn("强制终止子进程: {}", describe(command));
                process.destroyForcibly();
            }
        }
    }

    private static void drain(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    output.append(line).append(System.lineSeparator());
                }
            }
        } catch (IOException e) {
            // 超时强杀会关闭管道并在此抛异常,属于预期路径
            log.debug("读取子进程输出结束: {}", e.getMessage());
        }
    }

    private static String describe(List<String> command) {
        return command.isEmpty() ? "(空命令)" : command.get(0);
    }

    /**
     * 子进程执行结果
     */
    public static final class Result {
        private final int exitCode;
        private final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
