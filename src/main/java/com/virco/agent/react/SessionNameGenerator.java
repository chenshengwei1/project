package com.virco.agent.react;

/**
 * 会话标题生成器。
 * 职责单一：把一段用户输入转成可读的会话名。
 * 不依赖 Spring、不依赖 ChatMessage 结构，纯函数，方便复用和单测。
 */
public final class SessionNameGenerator {

    private static final int MAX_LEN = 30;
    private static final String DEFAULT_NAME = "未命名会话";

    private SessionNameGenerator() {}

    /**
     * 从用户输入生成会话名。
     * 规则：去控制字符 → 压缩空白 → 截断到 MAX_LEN（按码点，不会切坏 emoji）。
     */
    public static String fromFirstUserMessage(String userInput) {
        if (userInput == null) return DEFAULT_NAME;
        String s = userInput
            .replaceAll("\\p{Cntrl}", " ")    // 去控制字符（换行、制表等）
            .replaceAll("\\s+", " ")          // 压缩连续空白
            .strip();
        if (s.isEmpty()) return DEFAULT_NAME;
        if (s.codePointCount(0, s.length()) <= MAX_LEN) return s;
        int end = s.offsetByCodePoints(0, MAX_LEN);
        return s.substring(0, end) + "…";
    }

    /**
     * 从消息列表取首条 user 消息并生成名称。
     * 如果 SessionStore 里已经有 firstUserMessage()，用那个即可，此处为便捷重载。
     */
    public static String fromMessages(java.util.List<com.virco.agent.model.ChatMessage> messages) {
        if (messages == null) return DEFAULT_NAME;
        return messages.stream()
            .filter(m -> "user".equals(m.role()))
            .map(com.virco.agent.model.ChatMessage::content)
            .filter(c -> c != null && !c.isBlank())
            .findFirst()
            .map(SessionNameGenerator::fromFirstUserMessage)
            .orElse(DEFAULT_NAME);
    }
}