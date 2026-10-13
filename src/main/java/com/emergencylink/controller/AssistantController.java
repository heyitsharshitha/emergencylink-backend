package com.emergencylink.controller;

import com.emergencylink.dto.AssistantDtos.*;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.AssistantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantService assistant;
    public AssistantController(AssistantService assistant) { this.assistant = assistant; }

    @GetMapping("/prompts")
    public List<SuggestedPrompt> prompts() { return assistant.suggestedPrompts(CurrentUser.get()); }

    @GetMapping("/mode")
    public Map<String, String> mode() { return Map.of("mode", assistant.liveMode() ? "LIVE" : "DEMO"); }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req) { return assistant.chat(CurrentUser.get(), req); }
}
