package com.virco.agent.service.impl;

import com.virco.agent.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Component
public class FileStorage {

    private final Path root;
    private final String urlPrefix;

    public FileStorage(AppProperties props) throws Exception {
        this.root = Paths.get(props.getUpload().getDir()).toAbsolutePath();
        this.urlPrefix = props.getUpload().getUrlPrefix();
        Files.createDirectories(root);
    }

    public Stored store(String ownerId, MultipartFile file) {
        try {
            String ext = "";
            String name = file.getOriginalFilename();
            if (name != null && name.contains(".")) {
                ext = name.substring(name.lastIndexOf('.'));
            }
            String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
            Path target = root.resolve(storedName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Stored(urlPrefix + "/" + storedName, target.toString());
        } catch (Exception e) {
            throw new RuntimeException("failed to store file", e);
        }
    }

    public void deleteQuietly(String url) {
        try {
            if (url == null) return;
            String name = url.substring(url.lastIndexOf('/') + 1);
            Files.deleteIfExists(root.resolve(name));
        } catch (Exception ignore) {}
    }

    public record Stored(String url, String path) {}
}