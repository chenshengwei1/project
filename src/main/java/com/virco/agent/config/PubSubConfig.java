package com.virco.agent.config;


import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class PubSubConfig {

    public static final String CHANNEL = "order:events";
    public static final String USER_CHANNEL = "User:events";

    public record Subscription(String channel, MessageListener listener) {}

    /** 消息监听器容器 */
    @Bean
    public RedisMessageListenerContainer listenerContainer(
            RedisConnectionFactory factory,
            OrderEventListener orderlistener,
            List<Subscription> subscriptions) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(new MessageListenerAdapter(orderlistener, "onMessage"), new ChannelTopic(CHANNEL));
        for (Subscription s : subscriptions) {
            container.addMessageListener(s.listener(), new ChannelTopic(s.channel()));
        }
        return container;
    }

    @Bean 
    public Subscription userSubscription(UserEventListener userlistener) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(userlistener, "onMessage");
        adapter.afterPropertiesSet(); // 关键：初始化内部 MethodInvoker
        return new Subscription(USER_CHANNEL, adapter);
    }

}