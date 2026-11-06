package com.virco.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.virco.agent.config.AgentProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Agnes Video 2.5 视频生成 Agent 工具。
 *
 * 接口为异步任务模式：
 *   1) POST https://apihub.agnes-ai.com/videos            创建任务，返回 video_id
 *   2) GET  https://apihub.agnes-ai.com/agnesapi?...     轮询任务，直到 completed / failed
 *   3) 从 completed 响应的 url 字段下载视频
 *
 * 生成结果统一保存到 {workDir}/videos，其中 workDir = agent.tools().workspaceRoot()。
 */
@Service 
public class AgnesVideoTools {

    private static final String CREATE_URL = "https://apihub.agnes-ai.com/videos";
    private static final String QUERY_URL = "https://apihub.agnes-ai.com/agnesapi";
    private static final String MODEL = "agnes-video-2.5";

    /** 创建任务超时（秒） */
    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(60);
    /** 查询任务超时（秒） */
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);
    /** 下载视频超时（秒） */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(300);
    /** 轮询间隔（毫秒），文档建议 1–2 秒 */
    private static final long POLL_INTERVAL_MS = 1500L;
    /** 命中 429 后的退避时间（毫秒） */
    private static final long RATE_LIMIT_BACKOFF_MS = 3000L;

    private final AgentProperties agent;
    private final Path videosDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public AgnesVideoTools(AgentProperties agent) {
        this.agent = agent;
        var cfg = agent.tools();
        
        Path workDir = Path.of(cfg.workspaceRoot()).toAbsolutePath().normalize();
        this.videosDir = workDir.resolve("videos");
        try {
            Files.createDirectories(videosDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建视频目录: " + videosDir, e);
        }
    }

    // =====================================================================
    // 一、创建任务：三种模式
    // =====================================================================

    /**
     * 文生视频。mode = "text"：禁止携带 first_frame / last_frame / images / audios / videos。
     */
    @AgentTool(description = "根据文本提示词创建视频生成任务，返回 video_id，后续需轮询查询结果")
    public String createTextToVideo(
            @ToolParam(description = "视频内容描述，包含主体、动作、镜头、风格等") String prompt,
            @ToolParam(description = "时长，字符串 \"4\"–\"12\"", required = false) String seconds,
            @ToolParam(description = "分辨率档位：720P / 1080P / 1K / 2K", required = false) String size,
            @ToolParam(description = "画幅比例，如 16:9 / 9:16 / 1:1", required = false) String aspectRatio,
            @ToolParam(description = "随机种子，相同种子提高可复现性", required = false) Integer seed
    ) throws IOException, InterruptedException {
        ObjectNode body = baseBody(prompt, seconds, size, aspectRatio);
        body.put("mode", "text");
        if (seed != null) {
            body.put("seed", seed);
        }
        return createTask(body);
    }

    /**
     * 首尾帧控制视频。mode = "keyframe"：first_frame / last_frame 至少提供一个，
     * 且禁止使用 images / audios / videos。
     */
    @AgentTool(description = "使用首帧和/或尾帧约束视频生成，返回 video_id")
    public String createKeyframeVideo(
            @ToolParam(description = "视频内容描述，说明首尾帧之间的过渡与运动") String prompt,
            @ToolParam(description = "首帧图片 HTTPS URL，与 lastFrame 至少提供一个", required = false) String firstFrame,
            @ToolParam(description = "尾帧图片 HTTPS URL，与 firstFrame 至少提供一个", required = false) String lastFrame,
            @ToolParam(description = "时长，字符串 \"4\"–\"12\"", required = false) String seconds,
            @ToolParam(description = "分辨率档位", required = false) String size,
            @ToolParam(description = "画幅比例", required = false) String aspectRatio
    ) throws IOException, InterruptedException {
        if (isBlank(firstFrame) && isBlank(lastFrame)) {
            throw new IllegalArgumentException("keyframe 模式至少需要 firstFrame 或 lastFrame 之一");
        }
        validateHttps(firstFrame, "firstFrame");
        validateHttps(lastFrame, "lastFrame");

        ObjectNode body = baseBody(prompt, seconds, size, aspectRatio);
        body.put("mode", "keyframe");
        if (!isBlank(firstFrame)) {
            body.put("first_frame", firstFrame);
        }
        if (!isBlank(lastFrame)) {
            body.put("last_frame", lastFrame);
        }
        return createTask(body);
    }

    /**
     * 多模态参考视频。mode = "reference"：images / audios / videos 至少一类非空，
     * 且禁止使用 first_frame / last_frame。
     */
    @AgentTool(description = "使用图片/音频/视频进行参考生成，返回 video_id")
    public String createReferenceVideo(
            @ToolParam(description = "视频内容描述，用 [Image 1]、[Video 1] 等占位符引用素材") String prompt,
            @ToolParam(description = "参考图片 HTTPS URL 列表，最多 8 张", required = false) List<String> images,
            @ToolParam(description = "参考音频 HTTPS URL 列表，最多 3 段", required = false) List<String> audios,
            @ToolParam(description = "参考视频 HTTPS URL，最多 1 个", required = false) String videoUrl,
            @ToolParam(description = "参考视频起始秒数，默认 0", required = false) Double videoStartSeconds,
            @ToolParam(description = "参考视频是否必须包含音轨，默认 false", required = false) Boolean videoRequireAudio,
            @ToolParam(description = "时长，字符串 \"4\"–\"12\"", required = false) String seconds,
            @ToolParam(description = "分辨率档位", required = false) String size,
            @ToolParam(description = "画幅比例", required = false) String aspectRatio
    ) throws IOException, InterruptedException {
        int imageCount = images == null ? 0 : images.size();
        int audioCount = audios == null ? 0 : audios.size();
        int videoCount = isBlank(videoUrl) ? 0 : 1;

        if (imageCount == 0 && audioCount == 0 && videoCount == 0) {
            throw new IllegalArgumentException("reference 模式至少需要 images、audios 或 videoUrl 之一");
        }
        if (imageCount > 8) {
            throw new IllegalArgumentException("参考图片最多 8 张，当前 " + imageCount);
        }
        if (audioCount > 3) {
            throw new IllegalArgumentException("参考音频最多 3 段，当前 " + audioCount);
        }
        if (imageCount + audioCount + videoCount > 12) {
            throw new IllegalArgumentException("参考媒体总数不能超过 12 个，当前 "
                    + (imageCount + audioCount + videoCount));
        }
        if (images != null) {
            images.forEach(u -> validateHttps(u, "images"));
        }
        if (audios != null) {
            audios.forEach(u -> validateHttps(u, "audios"));
        }
        validateHttps(videoUrl, "videoUrl");

        ObjectNode body = baseBody(prompt, seconds, size, aspectRatio);
        body.put("mode", "reference");

        if (imageCount > 0) {
            var arr = body.putArray("images");
            images.forEach(arr::add);
        }
        if (audioCount > 0) {
            var arr = body.putArray("audios");
            audios.forEach(arr::add);
        }
        if (videoCount > 0) {
            ObjectNode v = mapper.createObjectNode();
            v.put("url", videoUrl);                     // 必须是 url，不能是 video_url / video_path
            v.put("start_seconds", videoStartSeconds == null ? 0 : videoStartSeconds);
            v.put("require_audio", Boolean.TRUE.equals(videoRequireAudio));
            body.putArray("videos").add(v);
        }
        return createTask(body);
    }

    // =====================================================================
    // 二、轮询任务并下载
    // =====================================================================

    /**
     * 轮询任务直至 completed 或 failed；成功后下载视频到 {workDir}/videos。
     *
     * @param maxWaitSeconds 最大等待秒数，默认 600
     */
    @AgentTool(description = "轮询视频任务直至完成或失败，成功后下载视频到本地 videos 目录")
    public String waitAndDownload(
            @ToolParam(description = "创建任务返回的 video_id") String videoId,
            @ToolParam(description = "最大等待秒数，默认 600", required = false) Integer maxWaitSeconds
    ) throws IOException, InterruptedException {
        if (isBlank(videoId)) {
            throw new IllegalArgumentException("videoId 不能为空");
        }
        String apiKey = requireApiKey();
        int maxWait = maxWaitSeconds == null ? 600 : maxWaitSeconds;
        long deadline = System.currentTimeMillis() + maxWait * 1000L;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);

            String query = QUERY_URL
                    + "?video_id=" + URLEncoder.encode(videoId, StandardCharsets.UTF_8)
                    + "&model_name=" + URLEncoder.encode(MODEL, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(query))
                    .timeout(QUERY_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                Thread.sleep(RATE_LIMIT_BACKOFF_MS);
                continue;
            }
            if (response.statusCode() != 200) {
                throw new IOException("查询视频任务失败 " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            String status = root.path("status").asText("");

            if ("completed".equalsIgnoreCase(status)) {
                String url = root.path("url").asText(null);
                if (isBlank(url)) {
                    throw new IOException("任务已完成但响应中没有 url: " + response.body());
                }
                Path saved = downloadVideo(url, videoId);
                return "视频生成成功"
                        + "\nvideo_id: " + videoId
                        + "\n本地路径: " + saved.toAbsolutePath()
                        + "\n视频 URL: " + url;
            }
            if ("failed".equalsIgnoreCase(status)) {
                String msg = root.path("error").path("message").asText("视频生成失败");
                throw new IOException("视频任务失败 [" + videoId + "]: " + msg);
            }
            // queued / in_progress：继续轮询
        }
        throw new IOException("视频任务超时，已等待 " + maxWait + " 秒: " + videoId);
    }

    // =====================================================================
    // 三、一站式工具
    // =====================================================================

    @AgentTool(description = "文生视频一站式工具：创建任务并轮询下载")
    public String generateVideoFromText(
            @ToolParam(description = "视频描述") String prompt,
            @ToolParam(description = "时长，字符串 \"4\"–\"12\"", required = false) String seconds,
            @ToolParam(description = "分辨率档位", required = false) String size,
            @ToolParam(description = "画幅比例", required = false) String aspectRatio
    ) throws IOException, InterruptedException {
        String videoId = createTextToVideo(prompt, seconds, size, aspectRatio, null);
        return waitAndDownload(videoId, 600);
    }

    @AgentTool(description = "首尾帧视频一站式工具：创建任务并轮询下载")
    public String generateVideoFromKeyframes(
            @ToolParam(description = "视频描述") String prompt,
            @ToolParam(description = "首帧 HTTPS URL", required = false) String firstFrame,
            @ToolParam(description = "尾帧 HTTPS URL", required = false) String lastFrame,
            @ToolParam(description = "时长，字符串 \"4\"–\"12\"", required = false) String seconds,
            @ToolParam(description = "分辨率档位", required = false) String size,
            @ToolParam(description = "画幅比例", required = false) String aspectRatio
    ) throws IOException, InterruptedException {
        String videoId = createKeyframeVideo(prompt, firstFrame, lastFrame, seconds, size, aspectRatio);
        return waitAndDownload(videoId, 600);
    }

    @AgentTool(description = "多模态参考视频一站式工具：创建任务并轮询下载")
    public String generateVideoFromReference(
            @ToolParam(description = "视频描述，用 [Image 1] 等占位符引用素材") String prompt,
            @ToolParam(description = "参考图片 HTTPS URL 列表", required = false) List<String> images,
            @ToolParam(description = "参考音频 HTTPS URL 列表", required = false) List<String> audios,
            @ToolParam(description = "参考视频 HTTPS URL", required = false) String videoUrl,
            @ToolParam(description = "时长，字符串 \"4\"–\"12\"", required = false) String seconds,
            @ToolParam(description = "分辨率档位", required = false) String size,
            @ToolParam(description = "画幅比例", required = false) String aspectRatio
    ) throws IOException, InterruptedException {
        String videoId = createReferenceVideo(prompt, images, audios, videoUrl,
                null, null, seconds, size, aspectRatio);
        return waitAndDownload(videoId, 600);
    }

    // =====================================================================
    // 四、内部辅助
    // =====================================================================

    /** 构造通用请求体：model / prompt / seconds / size / aspect_ratio。 */
    private ObjectNode baseBody(String prompt, String seconds, String size, String aspectRatio) {
        if (isBlank(prompt)) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        String sec = isBlank(seconds) ? "5" : seconds;
        try {
            int s = Integer.parseInt(sec);
            if (s < 4 || s > 12) {
                throw new IllegalArgumentException("seconds 必须在 \"4\"–\"12\" 之间，当前: " + sec);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("seconds 必须是字符串形式的整数 \"4\"–\"12\"，当前: " + sec);
        }

        String sz = isBlank(size) ? "720P" : size;
        if (!List.of("720P", "1080P", "1K", "2K").contains(sz)) {
            throw new IllegalArgumentException("size 只支持 720P / 1080P / 1K / 2K，当前: " + sz);
        }
        if (!isBlank(aspectRatio) && "auto".equalsIgnoreCase(aspectRatio)) {
            throw new IllegalArgumentException("aspect_ratio 不能为 auto");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("prompt", prompt);
        body.put("seconds", sec);   // 字符串
        body.put("size", sz);
        if (!isBlank(aspectRatio)) {
            body.put("aspect_ratio", aspectRatio);
        }
        return body;
    }

    /** 发送创建任务请求，返回 video_id。 */
    private String createTask(ObjectNode body) throws IOException, InterruptedException {
        String apiKey = requireApiKey();

        HttpRequest request = HttpRequest.newBuilder(URI.create(CREATE_URL))
                .timeout(CREATE_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("创建视频任务失败 " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        String videoId = root.path("video_id").asText(null);
        if (isBlank(videoId)) {
            videoId = root.path("id").asText(null);   // 兼容回退
        }
        if (isBlank(videoId)) {
            throw new IOException("创建响应中没有 video_id/id: " + response.body());
        }
        return videoId;
    }

    /** 下载视频到 {workDir}/videos/{videoId}.mp4。 */
    private Path downloadVideo(String videoUrl, String videoId) throws IOException, InterruptedException {
        URI uri = URI.create(videoUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("仅支持 HTTPS 视频 URL: " + videoUrl);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("下载视频失败，HTTP " + response.statusCode() + ": " + videoUrl);
        }

        String fileName = safeFileName(videoId) + ".mp4";
        Path target = videosDir.resolve(fileName);
        Files.write(target, response.body());
        return target;
    }

    private String requireApiKey() {
        String key = agent.tools().imageApiKey();
        if (isBlank(key)) {
            throw new IllegalStateException("未配置 Agnes API Key（agent.tools().agnesApiKey()）");
        }
        return key;
    }

    private void validateHttps(String url, String field) {
        if (isBlank(url)) {
            return;
        }
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException(field + " 必须是公共可访问的 HTTPS URL: " + url);
        }
    }

    private String safeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}