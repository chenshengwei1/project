
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.virco.agent.config.AgentProperties;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Agent 核心服务
 * <p>
 * 对应原 dsagent 的 agent_core.py
 * 负责与 DeepSeek API 通信，管理对话流程
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    @Autowired
    private AgentProperties properties;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private ToolService toolService;

    private OkHttpClient httpClient;
    private ObjectMapper objectMapper;
    private String apiKey;
    private String apiUrl;
    private String model;
    private int maxTokens;
    private double temperature;

    @PostConstruct
    public void init() {
        AgentProperties.DeepSeekConfig deepseek = properties.getDeepseek();
        this.apiKey = deepseek.getApiKey();
        this.apiUrl = deepseek.getApiUrl();
        this.model = deepseek.getModel();
        this.maxTokens = deepseek.getMaxTokens();
        this.temperature = deepseek.getTemperature();
        this.objectMapper = new ObjectMapper();

        // 创建 HTTP 客户端
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(deepseek.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(deepseek.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(deepseek.getWriteTimeout(), TimeUnit.MILLISECONDS)
                .build();

        log.info("AgentService 初始化完成");
        log.debug("API URL: {}", apiUrl);
        log.debug("Model: {}", model);
        log.debug("Max Tokens: {}", maxTokens);
        log.debug("Temperature: {}", temperature);
        log.debug("API Key: {}", maskApiKey(apiKey));
    }

    // ============================================================
    // 核心对话方法
    // ============================================================

    /**
     * 处理用户消息并返回 AI 回复
     * 对应原 dsagent 的 process_message 方法
     */
    public ChatMessage processMessage(String sessionId, String userMessage) {
        long startTime = System.currentTimeMillis();
        log.info("============================================================");
        log.info("处理用户消息 - sessionId: {}, 消息长度: {}", sessionId, userMessage.length());
        log.debug("用户消息内容: {}", userMessage);

        try {
            // 1. 获取或创建会话
            sessionId = memoryService.getOrCreateSession(sessionId);

            // 2. 保存用户消息
            ChatMessage userMsg = ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("user")
                    .content(userMessage)
                    .build();
            memoryService.saveMessage(userMsg);
            log.debug("用户消息已保存");

            // 3. 获取历史消息
            List<ChatMessage> history = memoryService.getSessionMessages(sessionId);
            log.debug("历史消息数: {}", history.size());

            // 4. 构建请求消息列表
            ArrayNode messages = buildMessages(history);

            // 5. 调用 DeepSeek API
            log.info("正在调用 DeepSeek API...");
            String responseContent = callDeepSeekAPI(messages);
            log.debug("API 返回内容长度: {}", responseContent.length());

            // 6. 保存 AI 回复
            ChatMessage assistantMsg = ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(responseContent)
                    .build();
            memoryService.saveMessage(assistantMsg);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("消息处理完成 - 耗时: {}ms", elapsed);

            return assistantMsg;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("消息处理失败 - 耗时: {}ms", elapsed, e);

            // 返回错误消息
            ChatMessage errorMsg = ChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content("抱歉，处理您的消息时出现了错误：\n\n```\n" + e.getMessage() + "\n```\n\n请稍后重试。")
                    .build();

            try {
                memoryService.saveMessage(errorMsg);
            } catch (Exception ex) {
                log.warn("保存错误消息失败: {}", ex.getMessage());
            }

            return errorMsg;
        }
    }

    // ============================================================
    // DeepSeek API 调用
    // ============================================================

    /**
     * 调用 DeepSeek API
     */
    private String callDeepSeekAPI(ArrayNode messages) throws IOException {
        long apiStartTime = System.currentTimeMillis();
        log.debug("构建 API 请求...");

        // 构建请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.set("messages", messages);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        log.debug("请求体大小: {} bytes", jsonBody.length());

        // 构建 HTTP 请求
        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        log.info("发送 API 请求 - URL: {}, Model: {}", apiUrl, model);

        // 执行请求
        try (Response response = httpClient.newCall(request).execute()) {
            long apiElapsed = System.currentTimeMillis() - apiStartTime;

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "未知错误";
                log.error("API 请求失败 - HTTP {}: {}, 耗时: {}ms",
                        response.code(), errorBody, apiElapsed);
                throw new IOException("API 请求失败 (HTTP " + response.code() + "): " + errorBody);
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            log.debug("API 响应大小: {} bytes, 耗时: {}ms", responseBody.length(), apiElapsed);

            // 解析响应
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode choices = rootNode.get("choices");

            if (choices == null || choices.isEmpty()) {
                log.error("API 返回空结果 - 完整响应: {}", responseBody);
                throw new IOException("API 返回空结果");
            }

            String content = choices.get(0).get("message").get("content").asText();

            // 记录 token 使用情况
            JsonNode usage = rootNode.get("usage");
            if (usage != null) {
                log.info("Token 使用 - prompt: {}, completion: {}, total: {}",
                        usage.get("prompt_tokens").asInt(),
                        usage.get("completion_tokens").asInt(),
                        usage.get("total_tokens").asInt());
            }

            log.info("API 调用成功 - 耗时: {}ms", apiElapsed);
            return content;
        }
    }

    // ============================================================
    // 消息构建
    // ============================================================

    /**
     * 构建 API 请求的消息列表
     */
    private ArrayNode buildMessages(List<ChatMessage> history) {
        ArrayNode messages = objectMapper.createArrayNode();

        // 添加系统提示词
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", buildSystemPrompt());

        // 添加历史消息
        for (ChatMessage msg : history) {
            ObjectNode message = messages.addObject();
            message.put("role", msg.getRole());
            message.put("content", msg.getContent());
        }

        log.debug("构建消息列表完成 - 共 {} 条消息（含系统提示词）", messages.size());
        return messages;
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return "你是一个名为 '高级开发人员' 的智能开发助手，专门负责维护用户的 Node.js + Express 或者 Python + Flask 项目。\n\n"
                + "## 你的角色定位\n"
                + "你像一位干劲十足、诚实可靠的'高级实习生'：\n"
                + "- 你有很强的自主工作能力，能独立完成任务\n"
                + "- 你会主动展示工作过程，让用户知道你在做什么\n"
                + "- 遇到不确定的事情，你会坦诚提问，而不是胡乱猜测\n"
                + "- 被指出问题时，你会承认并立刻改进，不找借口\n"
                + "- 你始终追求'交付完整成果'，而不只是'给建议'\n\n"
                + "## 工作原则\n"
                + "- **诚实第一**：不确定的事直接说'我不确定'，然后提出你认为可行的方案让用户选择\n"
                + "- **主动沟通**：任务较复杂或耗时较长时，主动告知预计时间\n"
                + "- **接受纠正**：用户指出问题时，先自检看看自己有没有做错，然后进行纠正\n"
                + "- **避免偷懒**：不要为了快速完成而牺牲质量，用户更看重准确性\n"
                + "- **交付物完整**：创建的文件要格式规范、内容完整、可直接使用\n\n"
                + "## 可用工具\n"
                + "你可以使用以下工具来帮助用户：\n"
                + "1. **readFile** - 读取文件内容\n"
                + "2. **writeFile** - 写入文件内容\n"
                + "3. **listDirectory** - 列出目录内容\n"
                + "4. **executeCommand** - 执行系统命令\n"
                + "5. **searchInProject** - 在项目中搜索关键词\n\n"
                + "请根据用户的需求，主动使用这些工具来完成任务。";
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 隐藏 API Key
     */
    private String maskApiKey(String key) {
        if (key == null || key.length() < 12) {
            return "未配置";
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }
}
