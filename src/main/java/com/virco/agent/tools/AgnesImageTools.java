package com.virco.agent.tools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.virco.agent.config.AgentProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j 
@Service 
public class AgnesImageTools {
    private final AgentProperties agent;
    private final Path imagesDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
            
    private static String ENDPOINT;
    private static final String MODEL = "agnes-image-2.5-flash";
    public AgnesImageTools(AgentProperties agent) {
        this.agent = agent;
        var cfg = agent.tools();
        Path workDir = Path.of(cfg.workspaceRoot()).toAbsolutePath().normalize();
        this.imagesDir = workDir.resolve("images");
        ENDPOINT = agent.tools().imageBaseEndpoint();
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建图片目录: " + imagesDir, e);
        }
    }


    private ObjectNode baseRequest(String prompt, String size, String ratio, String responseFormat) {
        log.debug("try to gen image for prompt={}", prompt);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("prompt", prompt);
        body.put("size", size);                // 推荐 1K / 2K / 3K / 4K
        if (ratio != null) body.put("ratio", ratio);

        ObjectNode extra = mapper.createObjectNode();
        extra.put("response_format", responseFormat);  // "url" 或 "b64_json"
        body.set("extra_body", extra);
        return body;
    }

    // @Tool 文生图
    @AgentTool(description = "根据文本提示词生成图像，返回图片 URL 并保存到本地 images 目录")
    public String textToImage(
            @JsonProperty("prompt") String prompt,
            @JsonProperty("size") String size,
            @JsonProperty("ratio") String ratio
    ) throws IOException, InterruptedException {
        String tier = size == null ? "2K" : size;
        ObjectNode body = baseRequest(prompt, tier, ratio, "url");
        return executeAndSave(body);
    }

    // @Tool 图生图（URL 输入）
    @AgentTool(description = "基于输入图片 URL 进行图生图编辑，返回生成图片 URL 并保存到本地")
    public String imageToImageFromUrl(
            @JsonProperty("prompt") @ToolParam(description = "图像编辑指令，描述要改变/保留的内容") String prompt,
            @JsonProperty("imageUrl") @ToolParam(description = "输入图片的公共 HTTPS URL") String imageUrl,
            @JsonProperty("size") @ToolParam(description = "尺寸档位，如 1K / 2K", required = false) String size,
            @JsonProperty("ratio") @ToolParam(description = "宽高比，如 1:1 / 16:9", required = false) String ratio
    ) throws IOException, InterruptedException {
        if (imageUrl == null || !imageUrl.startsWith("https://")) {
            throw new IllegalArgumentException("图生图的输入图片必须是可公开访问的 HTTPS URL");
        }
        ObjectNode body = baseRequest(prompt, size == null ? "2K" : size, ratio, "url");
        body.with("extra_body").putArray("image").add(imageUrl);
        return executeAndSave(body);
    }

    // @Tool 图生图（本地文件输入）
    @AgentTool(name = "imageToImageFromLocalFile", idempotent = false,
            description = "在本地工作目录执行 shell 命令并返回输出。用于查看文件、运行脚本、系统信息查询等。"
                + "参数 command 为完整命令，如 'ls -la' 或 'cat notes.txt'。"
                + "禁止执行破坏性命令（rm -rf、格式化磁盘等）。执行超时或危险命令会被拒绝。")
    public String imageToImageFromLocalFile(
            @JsonProperty("prompt") @ToolParam(description = "图像编辑指令") String prompt,
            @JsonProperty("localImagePath") @ToolParam(description = "本地图片文件路径（绝对路径或相对工作目录）") String localImagePath,
            @JsonProperty("size") @ToolParam(description = "尺寸档位", required = false) String size,
            @JsonProperty("ratio") @ToolParam(description = "宽高比", required = false) String ratio
    ) throws IOException, InterruptedException {
        Path source = Path.of(localImagePath);
        if (!source.isAbsolute()) source = imagesDir.getParent().resolve(source).normalize();
        if (!Files.exists(source)) throw new IllegalArgumentException("文件不存在: " + source);

        String mime = Files.probeContentType(source);
        if (mime == null || !mime.startsWith("image/")) mime = "image/png";
        byte[] bytes = Files.readAllBytes(source);
        String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);

        ObjectNode body = baseRequest(prompt, size == null ? "2K" : size, ratio, "url");
        body.with("extra_body").putArray("image").add(dataUri);
        return executeAndSave(body);
    }

    // 下载任意公共图片 URL 到 images 目录
    public Path downloadImage(String imageUrl) throws IOException, InterruptedException {
        URI uri = URI.create(imageUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("仅支持 HTTPS 图片 URL");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("下载图片失败，HTTP " + response.statusCode() + ": " + imageUrl);
        }
        String fileName = fileNameFromUrl(imageUrl);
        Path target = imagesDir.resolve(fileName);
        Files.write(target, response.body());
        return target;
    }

    private String fileNameFromUrl(String url) {
        String path = URI.create(url).getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isBlank() || !name.contains(".")) {
            name = "img_" + System.currentTimeMillis() + ".png";
        }
        return name;
    }


    // 内部：发送请求、解析响应、落盘
    private String executeAndSave(ObjectNode body) throws IOException, InterruptedException {
        String cfgApiKey = this.agent.tools().imageApiKey();
        if (cfgApiKey == null || cfgApiKey.isBlank()) {
            throw new IllegalStateException("未配置 Agnes API Key");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(300))     // 文档建议 60s–360s
                .header("Authorization", "Bearer " + cfgApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("to image respose:\n"+response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Agnes API 错误 " + response.statusCode() + ": " + response.body());
        }


        JsonNode root = mapper.readTree(response.body());
        JsonNode dataNode = root.path("data");
        if (!dataNode.isArray() || dataNode.isEmpty()) {
            throw new IOException("响应中没有 data 数组: " + response.body());
        }
        JsonNode item = dataNode.get(0);

        if (item.hasNonNull("url")) {
            String url = item.get("url").asText();
            Path saved = downloadImage(url);
            return "生成成功\n本地路径: " + saved.toAbsolutePath() + "\n图片 URL: " + url;
        }

        if (item.hasNonNull("b64_json")) {
            byte[] bytes = Base64.getDecoder().decode(item.get("b64_json").asText());
            Path target = imagesDir.resolve("img_" + System.currentTimeMillis() + ".png");
            Files.write(target, bytes);
            return "生成成功\n本地路径: " + target.toAbsolutePath();
        }

        throw new IOException("响应中既没有 url 也没有 b64_json: " + response.body());
    }
}
