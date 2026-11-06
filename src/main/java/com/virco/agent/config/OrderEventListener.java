package com.virco.agent.config;

import org.springframework.stereotype.Component;

import com.virco.agent.entity.ChatDTO.UserRequest;
import com.virco.agent.user.UserService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor 
public class OrderEventListener {

    private final UserService userService;
    /** 收到消息时被调用 */
    public void onMessage(String message) {
        System.out.println("收到订单事件: " + message);

        userService.add(new UserRequest());
    }
}
