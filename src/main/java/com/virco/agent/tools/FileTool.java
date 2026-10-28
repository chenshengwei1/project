package com.virco.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.config.AgentProperties;

@Service
public class FileTool {
    private final AgentProperties agent;
    private final ObjectMapper om;
    public FileTool(AgentProperties agent, ObjectMapper om) { this.agent = agent; this.om = om; }

    private Path resolve(String rel) throws IOException {
        Path root = Path.of(agent.tools().workspaceRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(rel == null ? "" : rel).normalize();
        if (!target.startsWith(root))
            throw new SecurityException("路径越界: " + rel);
        return target;
    }

    @AgentTool(name = "read_file",
        description = "读取工作目录下的文本文件。参数 path 为相对路径，如 'notes.txt' 或 'src/App.java'。")
    public String read(@JsonProperty("path") String path) throws IOException {
        Path p = resolve(path);
        if (!Files.exists(p)) return "{\"error\":\"NOT_FOUND\"}";
        long size = Files.size(p);
        if (size > agent.tools().fileMaxBytes()) return "{\"error\":\"FILE_TOO_LARGE\",\"size\":" + size + "}";
        return Files.readString(p);
    }

    @AgentTool(name = "write_file", idempotent = false,
        description = "向工作目录下的文本文件写入内容（覆盖）。参数 path 为相对路径，content 为完整内容。"
            + "如 'notes.txt'、'hello world'。会覆盖已有文件，请谨慎。")
    public String write(@JsonProperty("path") String path,
                        @JsonProperty("content") String content) throws IOException {
        Path p = resolve(path);
        Files.createDirectories(p.getParent() == null ? p : p.getParent());
        Files.writeString(p, content == null ? "" : content);
        return om.writeValueAsString(Map.of("ok", true, "path", path,
            "bytes", Files.size(p)));
    }
}