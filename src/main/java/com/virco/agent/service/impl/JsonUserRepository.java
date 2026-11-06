package com.virco.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.auth.PasswordHasher;
import com.virco.agent.config.AppProperties;
import com.virco.agent.entity.ChatDTO;
import com.virco.agent.entity.ChatDTO.User;
import com.virco.agent.entity.ChatDTO.UserRequest;
import com.virco.agent.user.UserService;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JsonUserRepository implements UserRepository {

    private final UserService userService;

    public JsonUserRepository( UserService userService) {
        this.userService = userService;
    }

    @Override
    public Optional<UserRecord> findByUsername(String username) {
        return userService.findAll().stream()
                .filter(u -> u.getName().equals(username))
                .findFirst()
                .map(u -> convert(u));
    }

    @Override
    public Optional<UserRecord> findById(String id) {
        return userService.findById(id).map(this::convert).
                map(u -> {
                    // 这里可以设置一些默认值或额外处理
                    return u;
                });
    }

    @Override
    public UserRecord save(UserRecord user) {
        userService.add(new UserRequest(user.id(), user.name(), user.email(), 18));
        return user;
    }

    /** 启动时若无任何用户，插入一个默认 admin/admin123，便于首次登录 */
    @PostConstruct
    void initDefaultUser() {
        if (findByUsername("admin").isEmpty()){
            UserRecord admin = new UserRecord(null, "admin",
                    PasswordHasher.hash("admin123"),
                    "管理员", null, "admin@example.com");
            save(admin);
        };
    }

    private User convert(UserRecord user){
         User u = new User();
        u.setId(user.id());
        u.setPasswordHash(user.passwordHash());
        u.setName(user.name());
        u.setEmail(user.email());
        return u;
    }   

    private UserRecord convert(User user){
        
        return new UserRecord(
                user.getId(), user.getName(), user.getPasswordHash(),
                user.getName(), null, user.getEmail());
    }


}