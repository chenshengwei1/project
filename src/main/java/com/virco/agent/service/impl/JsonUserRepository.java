package com.virco.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.auth.PasswordHasher;
import com.virco.agent.config.AppProperties;
import com.virco.agent.entity.ChatDTO;

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

    private final JsonStore store;

    public JsonUserRepository(ObjectMapper om, AppProperties props) {
        Path file = Paths.get(props.getData().getDir(), "users.json");
        this.store = new JsonStore(file, om, UserRecord.class);
    }

    @Override
    public Optional<UserRecord> findByUsername(String username) {
        return store.<UserRecord>values().stream()
                .filter(u -> u.username().equals(username))
                .findFirst();
    }

    @Override
    public Optional<UserRecord> findById(String id) {
        return Optional.ofNullable(store.<UserRecord>get(id));
    }

    @Override
    public UserRecord save(UserRecord user) {
        String id = user.id() != null ? user.id()
                : UUID.randomUUID().toString().replace("-", "");
        UserRecord toStore = new UserRecord(
                id, user.username(), user.passwordHash(),
                user.name(), user.avatarUrl(), user.email());
        store.put(id, toStore);
        return toStore;
    }

    /** 启动时若无任何用户，插入一个默认 admin/admin123，便于首次登录 */
    @PostConstruct
    void initDefaultUser() {
        if (store.values().isEmpty()) {
            save(new UserRecord(null, "admin",
                    PasswordHasher.hash("admin123"),
                    "管理员", null, "admin@example.com"));
        }
    }

}