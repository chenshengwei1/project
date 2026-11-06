package com.virco.agent.react;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonStore<T>{

    private final Path file;
    private final ObjectMapper om;
    private final Map<String, T> data = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Class<T> valueType;

    public JsonStore(Path file, ObjectMapper om, Class<T> valueType) {
        this.file = file;
        this.om = om;
        this.valueType = valueType;
        load();
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            var type = om.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, valueType);
            Map<String, T> loaded = om.readValue(file.toFile(), type);
            if (loaded != null) data.putAll(loaded);
        } catch (Exception e) {
            throw new RuntimeException("load json failed: " + file, e);
        }
    }

    public void save() {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            om.writeValue(tmp.toFile(), data);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("save json failed: " + file, e);
        } finally {
            lock.unlock();
        }
    }

    public T get(String id) { return (T) data.get(id); }

    public void put(String id, T value) { data.put(id, value); save(); }

    public T remove(String id) {
        T k = data.remove(id);
        if (data.remove(id) != null) 
            save(); 
        return k;
    }

    public List<T> values() {
        return data.values().stream().map(v -> (T) v).toList();
    }

    public void clear() { if (!data.isEmpty()) { data.clear(); save(); } }
}