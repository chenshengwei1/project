package com.virco.agent.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.virco.agent.config.AgentProperties;

@Service
public class CommandTool {
    private static final Logger log = LoggerFactory.getLogger(CommandTool.class);
    private final AgentProperties agent;

    public CommandTool(AgentProperties agent) { this.agent = agent; }



    // ===== 类级别成员 =====

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** 单次命令最大采集输出，防止 OOM；可改成从 cfg 读取 */
    private static final int MAX_OUTPUT_BYTES = 1 << 20; // 1 MiB
    private static final long READER_WAIT_MS = 5_000;
    private static final long KILL_WAIT_MS = 3_000;

    /** 危险命令子串（按平台初始化一次） */
    private static final List<String> BLOCKED = IS_WINDOWS
            ? List.of("mkfs", "dd if=", ":(){:|:&};:", "shutdown", "reboot",
                    "format c:", "format d:", "del /f /s /q", "rd /s /q", "rmdir /s /q")
            : List.of("mkfs", "dd if=", ":(){:|:&};:", "shutdown", "reboot",
                    "del /f /s /q", "rd /s /q", "rmdir /s /q");

    /** rm -rf / 的精确匹配，避免误杀 rm -rf /tmp/xxx */
    private static final Pattern RM_ROOT = Pattern.compile(
            "(?i)(^|[;&|]\\s*)rm\\s+(-[a-z]+\\s+)*-(rf|fr)[a-z]*\\s+/(\\s|$|\\*)");

    // ===== 主方法 =====

    @AgentTool(name = "run_command", idempotent = false,
            description = "在本地工作目录执行 shell 命令并返回输出。用于查看文件、运行脚本、系统信息查询等。"
                + "参数 command 为完整命令，如 'ls -la' 或 'cat notes.txt'。"
                + "禁止执行破坏性命令（rm -rf、格式化磁盘等）。执行超时或危险命令会被拒绝。")
    public String run(@JsonProperty("command") String command) throws Exception {
        var cfg = agent.tools();
        if (!cfg.commandEnabled()) return err("COMMAND_DISABLED", null);
        if (command == null || command.isBlank()) return err("EMPTY_COMMAND", null);

        String cmd = command.trim();

        // 1) 危险命令
        String hit = findDangerous(cmd);
        if (hit != null) return err("DANGEROUS_COMMAND", hit);

        // 2) 白名单（支持路径取文件名）
        if (!cfg.commandAllowlist().isEmpty() && !allowlisted(cmd, cfg.commandAllowlist())) {
            return err("COMMAND_NOT_ALLOWED", firstToken(cmd));
        }

        // 3) 工作目录
        Path workDir;
        try {
            workDir = Path.of(cfg.workspaceRoot()).toAbsolutePath().normalize();
            Files.createDirectories(workDir);
        } catch (Exception e) {
            return err("WORKDIR_UNAVAILABLE", e.getMessage());
        }

        // 4) 构造进程：按平台 shell；stdin 重定向到空设备，避免 cat 等挂住
        ProcessBuilder pb = newShellProcess(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(
                IS_WINDOWS ? new File("NUL") : new File("/dev/null")));
        if (!IS_WINDOWS) pb.environment().putIfAbsent("LANG", "C.UTF-8");

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return err("START_FAILED", e.getMessage());
        }

        // 5) 先起读流线程（虚拟线程），再 waitFor，避免管道缓冲区满导致死锁
        Process p = proc;
        CompletableFuture<String> outFuture = CompletableFuture.supplyAsync(() -> {
            try (InputStream in = p.getInputStream()) {
                return drainAndLimit(in, MAX_OUTPUT_BYTES);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });

        boolean finished;
        try {
            finished = p.waitFor(cfg.commandTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(p);
            return err("INTERRUPTED", null);
        }
        if (!finished) {
            killTree(p);
            return err("TIMEOUT", null);
        }

        // 6) 进程已退出，等读流把剩余输出读完
        String out;
        try {
            out = outFuture.get(READER_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            killTree(p);
            return err("READ_OUTPUT_FAILED", e.getMessage());
        }

        int exit = p.exitValue();
        log.info("tool.command cmd={} exit={} outLen={}", cmd, exit, out.length());
        return out + "\n[exit=" + exit + "]";
    }

    // ===== 辅助方法 =====

    /** 按操作系统构造 shell；Windows 额外 chcp 65001 统一 UTF-8 输出 */
    private static ProcessBuilder newShellProcess(String command) {
        if (IS_WINDOWS) {
            String comspec = System.getenv("ComSpec");
            String exe = (comspec == null || comspec.isBlank()) ? "cmd.exe" : comspec;
            // /d 跳过 AutoRun，/s 使引号解析更可预测，chcp 65001 统一 UTF-8
            return new ProcessBuilder(exe, "/d", "/s", "/c", "chcp 65001>nul & " + command);
        }
        return new ProcessBuilder("/bin/sh", "-c", command);
    }

    /** 读到 maxBytes 后继续丢弃剩余数据直到 EOF，防止进程写管道阻塞，同时限制内存 */
    private static String drainAndLimit(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] chunk = new byte[8192];
        int n;
        boolean truncated = false;
        while ((n = in.read(chunk)) != -1) {
            if (truncated) continue; // 已截断，继续吸干
            int remaining = maxBytes - buf.size();
            if (remaining >= n) {
                buf.write(chunk, 0, n);
            } else {
                if (remaining > 0) buf.write(chunk, 0, remaining);
                truncated = true;
            }
        }
        String s = buf.toString(StandardCharsets.UTF_8);
        return truncated ? s + "\n...[output truncated, limit=" + maxBytes + " bytes]" : s;
    }

    /** 终止进程及其子进程，并等待退出 */
    private static void killTree(Process p) {
        List<ProcessHandle> children;
        try {
            children = p.descendants().toList();
        } catch (Exception e) {
            children = List.of();
        }
        children.forEach(h -> { try { h.destroyForcibly(); } catch (Exception ignored) {} });
        try { p.destroyForcibly(); } catch (Exception ignored) {}
        try { p.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static String findDangerous(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (String bad : BLOCKED) {
            if (lower.contains(bad)) return bad;
        }
        if (!IS_WINDOWS && RM_ROOT.matcher(command).find()) return "rm -rf /";
        return null;
    }

    private static boolean allowlisted(String command, List<String> allow) {
        String first = firstToken(command);
        if (allow.contains(first)) return true;
        try {
            String name = Path.of(first).getFileName().toString();
            if (allow.contains(name)) return true;
            int dot = name.lastIndexOf('.');
            return dot > 0 && allow.contains(name.substring(0, dot));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 取首个 token，支持带引号的路径，如 "C:\Program Files\x.exe" */
    private static String firstToken(String command) {
        String s = command.trim();
        if (s.isEmpty()) return s;
        char q = s.charAt(0);
        if (q == '"' || q == '\'') {
            int end = s.indexOf(q, 1);
            return end > 0 ? s.substring(1, end) : s.substring(1);
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return s.substring(0, i);
        }
        return s;
    }

    private static String err(String code, String message) {
        return message == null
                ? "{\"error\":\"" + code + "\"}"
                : "{\"error\":\"" + code + "\",\"message\":\"" + jsonEscape(message) + "\"}";
    }

    /** 最小 JSON 字符串转义，防止 first token / 异常消息里的引号、反斜杠破坏 JSON */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
