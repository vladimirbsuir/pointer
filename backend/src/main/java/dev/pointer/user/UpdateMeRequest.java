package dev.pointer.user;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateMeRequest(
        @Size(min = 1, max = 100) String displayName,
        @Pattern(regexp = Locales.PATTERN) String locale) {}
