package com.virco.agent.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.virco.agent.config.AgentProperties;
import com.virco.agent.model.ModelResolver;

/**
 * SiliconFlow 音频 Agent 工具。
 *
 * 覆盖 5 个能力：
 *   1) 上传参考音频        POST /uploads/audio/voice        multipart/form-data
 *   2) 创建语音（TTS）      POST /audio/speech               JSON → 二进制音频
 *   3) 获取音色列表        GET  /audio/voice/list           JSON
 *   4) 删除音色            POST /audio/voice/deletions      JSON
 *   5) 创建音频转录        POST /audio/transcriptions       multipart/form-data → JSON {text}
 *
 * 产物目录：
 *   {workDir}/audio  — 生成的语音文件
 *   {workDir}/tmp    — 上传参考音频时的临时文件
 */
@Service 
public class SiliconflowAudioTool {

    private static final String BASE = "https://api.siliconflow.cn/v1";
    private static final String UPLOAD_VOICE_URL = BASE + "/uploads/audio/voice";
    private static final String CREATE_SPEECH_URL = BASE + "/audio/speech";
    private static final String LIST_VOICE_URL = BASE + "/audio/voice/list";
    private static final String DELETE_VOICE_URL = BASE + "/audio/voice/deletions";
    private static final String TRANSCRIBE_URL = BASE + "/audio/transcriptions";

    private static final String DEFAULT_TTS_MODEL = "FunAudioLLM/CosyVoice2-0.5B";
    private static final String DEFAULT_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall";

    private final AgentProperties agent;
    private final ModelResolver resovler;
    private final Path audioDir;
    private final Path tmpDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public SiliconflowAudioTool(AgentProperties agent, ModelResolver resovler) {
        this.agent = agent;
        this.resovler = resovler;
        var cfg = agent.tools();
        Path workDir = Path.of(cfg.workspaceRoot()).toAbsolutePath().normalize();
        this.audioDir = workDir.resolve("audio");
        this.tmpDir = workDir.resolve("tmp");
        try {
            Files.createDirectories(audioDir);
            Files.createDirectories(tmpDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建音频目录: " + audioDir, e);
        }
    }

    // =====================================================================
    // 1. 上传参考音频
    // =====================================================================

    /**
     * 上传用户提供的音色风格，支持 base64 编码或文件格式。
     * 返回的 uri 可作为后续创建语音时的 voice 参数。
     *
     * 文档：POST /uploads/audio/voice
     * 必填：model、customName、text，以及 audio(base64) 或 file 二选一。
     */
    @AgentTool(description = "上传参考音频以创建自定义音色，返回 voice uri")
    public String uploadVoice(
            @ToolParam(description = "预定义音色风格模型名称，如 FunAudioLLM/CosyVoice2-0.5B") String model,
            @ToolParam(description = "自定义音色风格名称，如 my-voice") String customName,
            @ToolParam(description = "音频对应的文本内容") String text,
            @ToolParam(description = "本地音频文件路径（绝对路径或相对工作目录）", required = false) String audioFilePath,
            @ToolParam(description = "base64 编码音频，格式 data:audio/mpeg;base64,...", required = false) String audioBase64
    ) throws IOException, InterruptedException {
        if (isBlank(model)) model = DEFAULT_TTS_MODEL;
        if (isBlank(customName)) throw new IllegalArgumentException("customName 不能为空");
        if (isBlank(text)) throw new IllegalArgumentException("text 不能为空");
        if (isBlank(audioFilePath) && isBlank(audioBase64)) {
            throw new IllegalArgumentException("audioFilePath 和 audioBase64 必须至少提供一个");
        }

        String apiKey = requireApiKey();
        String boundary = "----SiliconFlowBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        writeFormField(body, boundary, "model", model);
        writeFormField(body, boundary, "customName", customName);
        writeFormField(body, boundary, "text", text);

        if (!isBlank(audioBase64)) {
            writeFormField(body, boundary, "audio", audioBase64);
        } else {
            Path file = resolvePath(audioFilePath);
            if (!Files.exists(file)) throw new IllegalArgumentException("音频文件不存在: " + file);
            String mime = Files.probeContentType(file);
            if (mime == null || !mime.startsWith("audio/")) mime = "audio/mpeg";
            writeFormFile(body, boundary, "file", file.getFileName().toString(), mime, Files.readAllBytes(file));
        }
        writeFormEnd(body, boundary);

        HttpRequest request = HttpRequest.newBuilder(URI.create(UPLOAD_VOICE_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("上传参考音频失败 " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        String uri = root.path("uri").asText(null);
        if (isBlank(uri)) {
            throw new IOException("上传响应中没有 uri: " + response.body());
        }
        return "上传成功\n音色名称: " + customName + "\nvoice uri: " + uri;
    }

    // =====================================================================
    // 2. 创建语音（TTS）
    // =====================================================================

    /**
     * 根据输入文本生成音频，返回二进制音频数据，保存到 {workDir}/audio。
     *
     * 文档：POST /audio/speech
     * voice 与 references 互斥；voice 不支持双音色，脚本化对话需用 references。
     */
    @AgentTool(description = "根据文本生成语音音频，保存到本地 audio 目录，返回文件路径")
    public String createSpeech(
            @ToolParam(description = "待合成文本") String input,
            @ToolParam(description = "音色，如 FunAudioLLM/CosyVoice2-0.5B:alex 或上传返回的 voice uri", required = false) String voice,
            @ToolParam(description = "模型名称，默认 FunAudioLLM/CosyVoice2-0.5B", required = false) String model,
            @ToolParam(description = "输出格式：mp3 / opus / wav / pcm，默认 mp3", required = false) String responseFormat,
            @ToolParam(description = "语速 0.25–4.0，默认 1.0", required = false) Double speed,
            @ToolParam(description = "音频增益 -10.0–10.0，默认 0.0", required = false) Double gain,
            @ToolParam(description = "采样率，mp3 默认 44100，wav/pcm 默认 44100，opus 固定 48000", required = false) Integer sampleRate,
            @ToolParam(description = "是否流式输出", required = false) Boolean stream
    ) throws IOException, InterruptedException {
        if (isBlank(input)) throw new IllegalArgumentException("input 不能为空");
        if (isBlank(voice)) voice = "FunAudioLLM/CosyVoice2-0.5B:alex";
        if (isBlank(model)) model = DEFAULT_TTS_MODEL;
        String format = isBlank(responseFormat) ? "mp3" : responseFormat.toLowerCase();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", input);
        body.put("voice", voice);
        body.put("response_format", format);
        if (speed != null) body.put("speed", speed);
        if (gain != null) body.put("gain", gain);
        if (sampleRate != null) body.put("sample_rate", sampleRate);
        if (stream != null) body.put("stream", stream);

        HttpRequest request = HttpRequest.newBuilder(URI.create(CREATE_SPEECH_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + requireApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String err = new String(response.body(), StandardCharsets.UTF_8);
            throw new IOException("创建语音失败 " + response.statusCode() + ": " + err);
        }

        Path target = audioDir.resolve("speech_" + System.currentTimeMillis() + "." + format);
        Files.write(target, response.body());
        return "语音生成成功\n本地路径: " + target.toAbsolutePath()
                + "\n格式: " + format + "\n大小: " + response.body().length + " bytes";
    }

    // =====================================================================
    // 3. 获取音色列表
    // =====================================================================

    /**
     * 获取用户自定义音色风格列表。
     *
     * 文档：GET /audio/voice/list
     * 响应 results 为预定义音色风格数组。
     */
    @AgentTool(description = "获取用户自定义音色列表")
    public String listVoices() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LIST_VOICE_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + requireApiKey())
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("获取音色列表失败 " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        ArrayNode results = (ArrayNode) root.path("results");
        if (results == null || results.isEmpty()) {
            return "当前没有自定义音色";
        }

        StringBuilder sb = new StringBuilder("自定义音色列表（共 ").append(results.size()).append(" 个）:\n");
        for (JsonNode v : results) {
            String uri = v.path("uri").asText("");
            String name = v.path("customName").asText("");
            String model = v.path("model").asText("");
            sb.append("- ").append(name).append("  uri=").append(uri).append("  model=").append(model).append("\n");
        }
        return sb.toString();
    }

    // =====================================================================
    // 4. 删除音色
    // =====================================================================

    /**
     * 删除用户自定义音色风格。
     *
     * 文档：POST /audio/voice/deletions
     * Body: { "uri": "speech:your-voice-name:xxx:xxxx" }
     */
    @AgentTool(description = "删除用户自定义音色，返回操作结果")
    public String deleteVoice(
            @ToolParam(description = "要删除的音色 uri，如 speech:your-voice-name:xxx:xxxx") String uri
    ) throws IOException, InterruptedException {
        if (isBlank(uri)) throw new IllegalArgumentException("uri 不能为空");

        ObjectNode body = mapper.createObjectNode();
        body.put("uri", uri);

        HttpRequest request = HttpRequest.newBuilder(URI.create(DELETE_VOICE_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + requireApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("删除音色失败 " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        String result = root.path("response").asText(root.toString());
        return "删除音色结果: " + result;
    }

    // =====================================================================
    // 5. 创建音频转录（ASR）
    // =====================================================================

    /**
     * 创建音频转录，返回转录文本。
     *
     * 文档：POST /audio/transcriptions
     * file 为音频文件对象（不是文件名），大小不超过 50MB，时长不超过 1 小时。
     * model 可选 FunAudioLLM/SenseVoiceSmall 或 TeleAI/TeleSpeechASR。
     */
    @AgentTool(description = "将音频文件转录为文本，返回转录结果")
    public String createTranscription(
            @ToolParam(description = "本地音频文件路径（绝对路径或相对工作目录）") String audioFilePath,
            @ToolParam(description = "ASR 模型，默认 FunAudioLLM/SenseVoiceSmall", required = false) String model
    ) throws IOException, InterruptedException {
        if (isBlank(audioFilePath)) throw new IllegalArgumentException("audioFilePath 不能为空");
        if (isBlank(model)) model = DEFAULT_ASR_MODEL;

        Path file = resolvePath(audioFilePath);
        if (!Files.exists(file)) throw new IllegalArgumentException("音频文件不存在: " + file);

        long size = Files.size(file);
        if (size > 50L * 1024 * 1024) {
            throw new IllegalArgumentException("音频文件超过 50MB 限制: " + size + " bytes");
        }

        String apiKey = requireApiKey();
        String boundary = "----SiliconFlowBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        writeFormField(body, boundary, "model", model);
        String mime = Files.probeContentType(file);
        if (mime == null || !mime.startsWith("audio/")) mime = "audio/mpeg";
        writeFormFile(body, boundary, "file", file.getFileName().toString(), mime, Files.readAllBytes(file));
        writeFormEnd(body, boundary);

        HttpRequest request = HttpRequest.newBuilder(URI.create(TRANSCRIBE_URL))
                .timeout(Duration.ofSeconds(300))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("创建音频转录失败 " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        String text = root.path("text").asText(null);
        if (text == null) {
            throw new IOException("转录响应中没有 text 字段: " + response.body());
        }
        return "转录成功\n文本: " + text;
    }

    // =====================================================================
    // 内部辅助
    // =====================================================================

    private void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFormFile(ByteArrayOutputStream out, String boundary, String name,
                               String fileName, String mime, byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFormEnd(ByteArrayOutputStream out, String boundary) throws IOException {
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private Path resolvePath(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p.normalize() : tmpDir.getParent().resolve(p).normalize();
    }

    private String requireApiKey() {
        String key = this.resovler.candidates().stream().findFirst().get().apiKey();
        if (isBlank(key)) {
            throw new IllegalStateException("未配置 SiliconFlow API Key（agent.tools().siliconflowApiKey()）");
        }
        return key;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
