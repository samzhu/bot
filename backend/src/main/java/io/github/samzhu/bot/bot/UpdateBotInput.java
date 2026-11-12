package io.github.samzhu.bot.bot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * GraphQL Mutation Input for updating a GKE Remote Bot definition.
 *
 * <p>OFFICIAL REFERENCE:
 * <ul>
 *   <li>Jakarta Bean Validation 3.1 Specification: https://jakarta.ee/specifications/bean-validation/3.1/</li>
 *   <li>Hibernate Validator 9.1: https://docs.jboss.org/hibernate/validator/9.0/reference/en-US/html_single/</li>
 *   <li>Checked: 2026-11-12</li>
 * </ul>
 *
 * <p>WHY / ARCHITECTURAL RATIONALE:
 * Java Record Compact Constructor normalizes 'id' and 'name' by trimming leading/trailing whitespace
 * prior to Bean Validation constraint checks (@Size, @NotBlank).
 * In contrast, 'description' and 'instructions' are preserved in their raw input form (including whitespace
 * and multi-line formatting), enforcing only maximum character bounds (2,000 and 10,000 respectively).
 */
public record UpdateBotInput(
    @NotNull(message = "Bot id is required")
    @NotBlank(message = "Bot id cannot be blank")
    String id,

    @NotBlank(message = "Bot name is required")
    @Size(min = 1, max = 100, message = "Bot name must be between 1 and 100 characters")
    String name,

    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    String description,

    @Size(max = 10000, message = "Instructions cannot exceed 10000 characters")
    String instructions
) {
    public UpdateBotInput {
        id = id != null ? id.trim() : null;
        name = name != null ? name.trim() : null;
    }
}
