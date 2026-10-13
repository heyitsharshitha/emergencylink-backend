package com.emergencylink.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AssistantDtos {
    private AssistantDtos() {}

    public record ChatTurn(@Size(max = 20) String role, @Size(max = 4000) String content) {}

    public record ChatRequest(@NotBlank(message = "Type a question first") @Size(max = 1000, message = "Keep questions under 1000 characters") String message,
                              Long referralId, Long hospitalId, @Valid List<ChatTurn> history) {}

    public record HospitalCard(Long id, String name, String city, Integer score, List<String> highlights, boolean resourcesStale,
                               Instant resourcesUpdatedAt) {}

    public record ChatResponse(String answer, String mode, List<String> sources, List<HospitalCard> hospitals,
                               List<String> dataNotes, Instant generatedAt) {}

    public record SuggestedPrompt(String label, String prompt, boolean needsReferral) {}
}
