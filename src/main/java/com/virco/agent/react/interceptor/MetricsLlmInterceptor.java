package com.virco.agent.react.interceptor;

import java.util.List;

import org.springframework.stereotype.Component;

import com.virco.agent.model.ChatMessage;
import com.virco.agent.react.LlmContext;

import lombok.extern.slf4j.Slf4j;

@Component 
@Slf4j 
public class MetricsLlmInterceptor implements LlmInterceptor {
    public void beforeLlm(LlmContext c) {
        List<ChatMessage> messages = (List<ChatMessage>)c.getAttributes().get("messages");
        if (messages != null && !messages.isEmpty()){
            log.info("LLM request: count="+messages.size()+" last message=");
            log.info(messages.get(messages.size() - 1).content());
        }else{
            log.info("NO LLM request - ");
        }
    }
    public void onFirstToken(LlmContext c) {

    }
    public void onLlmChunk(LlmContext c, String delta) {

    }
    public void afterLlm(LlmContext c) {
        log.info("\nLLM Response:");
        log.info(c.getResponse().content());
    }
    public void onLlmError(LlmContext c, Throwable e) {
        log.info("\nsome error when call LLM");
        log.error(e.getMessage(), e);
    }
}
