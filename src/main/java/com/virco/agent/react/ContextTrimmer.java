package com.virco.agent.react;

import java.util.ArrayList;
import java.util.List;

import com.virco.agent.model.ChatMessage;

public class ContextTrimmer {
    /** 保留 system + 最近 maxTurns 个"回合"，保证 assistant(tool_calls) 与 tool 配对 */
    public static List<ChatMessage> trim(List<ChatMessage> messages, int maxTurns) {
        if (messages.size() <= 1) return messages;
        List<ChatMessage> sys = messages.get(0).role().equals("system")
            ? List.of(messages.get(0)) : List.of();
        List<ChatMessage> rest = messages.subList(sys.size(), messages.size());
        // 从后往前找起点，保证起点不是 tool（会破坏配对）
        int start = Math.max(0, rest.size() - maxTurns * 2);
        while (start < rest.size() && rest.get(start).role().equals("tool")) start++;
        List<ChatMessage> tail = rest.subList(start, rest.size());
        List<ChatMessage> out = new ArrayList<>(sys);
        out.addAll(tail);
        return out;
    }
}
