package com.looptrip;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用户画像文件的唯一 IO 出口：data/profiles/{userId}.json。
 * ContextAssembler 不碰文件、Engine 不碰画像，都靠这条边界撑着。
 */
@Component
public class ProfileRepository {

    private final ObjectMapper objectMapper;
    private final PreferenceProperties properties;

    @Autowired
    public ProfileRepository(ObjectMapper objectMapper, PreferenceProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ProfileDocument load(String userId) {
        Path file = fileFor(userId);
        if (!Files.exists(file)) return ProfileDocument.empty(userId);
        try {
            ProfileDocument document = objectMapper.readValue(file.toFile(), ProfileDocument.class);
            return new ProfileDocument(userId, document.learningEnabled(), document.revisionHistory(),
                    document.candidates(), document.confirmed());
        } catch (IOException exception) {
            throw new UncheckedIOException("读取用户画像失败：" + file, exception);
        }
    }

    public void save(ProfileDocument document) {
        Path file = fileFor(document.userId());
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
        } catch (IOException exception) {
            throw new UncheckedIOException("写入用户画像失败：" + file, exception);
        }
    }

    public Path fileFor(String userId) {
        return Path.of(properties.storagePath()).resolve(userId + ".json");
    }
}
