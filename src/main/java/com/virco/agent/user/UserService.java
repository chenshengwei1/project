package com.virco.agent.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.virco.agent.entity.ChatDTO.*;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserService {

    private static final String USER_FILE = "user.json";

    private final ObjectMapper objectMapper;
    private final List<User> users = new ArrayList<>();
    private final Object lock = new Object();

    public UserService() {
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 应用启动时加载 user.json
     */
    @PostConstruct
    public void init() {
        synchronized (lock) {
            loadFromFile();
        }
    }

    public String safeValueAsString(User user) {
        try {
            return objectMapper.writeValueAsString(user);
        } catch(Exception e) {}
        return null;
    }

    public User safeValueAsString(String json){
        try {return objectMapper.readValue(json, User.class);}catch(Exception e){}
        return null;
    }

    // ---------- 核心 CRUD ----------

    public List<User> findAll() {
        synchronized (lock) {
            return new ArrayList<>(users);
        }
    }

    public List<User> search(String keyword) {
        synchronized (lock) {
            if (keyword == null || keyword.isBlank()) {
                return new ArrayList<>(users);
            }
            String kw = keyword.trim().toLowerCase();
            return users.stream()
                    .filter(u -> u.getId().toLowerCase().contains(kw)
                            || u.getName().toLowerCase().contains(kw)
                            || u.getEmail().toLowerCase().contains(kw))
                    .collect(Collectors.toList());
        }
    }

    public User findById(String id) {
        synchronized (lock) {
            return users.stream()
                    .filter(u -> u.getId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("用户不存在: " + id));
        }
    }

    public User add(UserRequest request) {
        synchronized (lock) {
            User user = new User();
            user.setId(request.getId() != null ? request.getId() : UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            user.setName(request.getName().trim());
            user.setEmail(request.getEmail().trim());
            user.setAge(request.getAge());
            user.setPasswordHash(request.getPasswordHash() != null ? request.getPasswordHash() : "");

            users.add(user);
            persist();
            return user;
        }
    }

    public User update(String id, UserRequest request) {
        synchronized (lock) {
            User user = users.stream()
                    .filter(u -> u.getId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("用户不存在: " + id));

            user.setName(request.getName().trim());
            user.setEmail(request.getEmail().trim());
            user.setAge(request.getAge());

            persist();
            return user;
        }
    }

    public User delete(String id) {
        synchronized (lock) {
            Iterator<User> it = users.iterator();
            while (it.hasNext()) {
                User u = it.next();
                if (u.getId().equals(id)) {
                    it.remove();
                    persist();
                    return u;
                }
            }
            throw new NoSuchElementException("用户不存在: " + id);
        }
    }

    public List<User> listUsers(long page, long size) {
        synchronized (lock) {
            return users.stream()
                    .skip(page * size)
                    .limit(size)
                    .collect(Collectors.toList());
        }
    }

    // ---------- 文件持久化 ----------

    private void loadFromFile() {
        Path path = Paths.get("data", "json", USER_FILE);
        if (!Files.exists(path)) {
            // 首次启动，写入示例数据
            users.add(new User("u1001", "张伟", "zhangwei@example.com", 28, ""));
            users.add(new User("u1002", "李娜", "lina@example.com", 32, ""));
            users.add(new User("u1003", "王强", "wangqiang@example.com", 25, ""));
            persist();
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) return;
            List<User> loaded = objectMapper.readValue(bytes, new TypeReference<List<User>>() {});
            users.clear();
            users.addAll(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("加载 user.json 失败", e);
        }
    }

    private void persist() {
        try {
            File file = new File(USER_FILE);
            objectMapper.writeValue(file, users);
        } catch (IOException e) {
            throw new IllegalStateException("保存到 user.json 失败", e);
        }
    }
}
