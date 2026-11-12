package io.github.samzhu.bot.bot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBotInput(
    @NotBlank(message = "Bot name is required")
    @Size(min = 1, max = 100, message = "Bot name must be between 1 and 100 characters")
    String name,

    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    String description,

    @Size(max = 10000, message = "Instructions cannot exceed 10000 characters")
    String instructions
) {
    public CreateBotInput {
        name = name != null ? name.trim() : null;
    }
}
