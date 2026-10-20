package com.example.aicsupport.validation;

import java.util.List;

public record ValidationResult(
        boolean passed,
        List<String> issues) {

    public ValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult failed(List<String> issues) {
        return new ValidationResult(false, issues);
    }
}
