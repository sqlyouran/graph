package com.looptrip;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final int chapter;
    private final String model;

    public MetaController(
            @Value("${looptrip.chapter}") int chapter,
            @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chapter = chapter;
        this.model = model;
    }

    @GetMapping
    public MetaResponse getMeta() {
        return new MetaResponse(
                chapter,
                model,
                List.of("http-api", "chat-client-ready", "direct-llm-call"),
                List.of("travel-planning", "tools", "agent-loop", "database"));
    }

    public record MetaResponse(
            int chapter,
            String model,
            List<String> capabilities,
            List<String> notDoneYet) {
    }
}
