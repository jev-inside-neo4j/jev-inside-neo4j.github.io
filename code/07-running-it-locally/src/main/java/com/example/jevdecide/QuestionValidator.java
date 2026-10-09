package com.example.jevdecide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks the questions map before any HTTP call, so a malformed request fails
 * fast and costs nothing.
 *
 * The shapes for "choice" and "score" are enforced strictly. Any other question
 * type is only checked for "instructions" and then passed through, because the
 * API is the authority on which other types exist.
 */
public final class QuestionValidator {

    static final int CHOICE_MIN = 1;
    static final int CHOICE_MAX = 255;
    static final int SCORE_MIN = 2;
    static final int SCORE_MAX = 10;

    private QuestionValidator() {}

    public static List<String> validate(Map<String, Object> questions) {
        List<String> problems = new ArrayList<>();

        if (questions == null || questions.isEmpty()) {
            problems.add("questions must be a non-empty map");
            return problems;
        }

        for (Map.Entry<String, Object> entry : questions.entrySet()) {
            String id = entry.getKey();
            if (id == null || id.isBlank()) {
                problems.add("question ids must not be blank");
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> question)) {
                problems.add(id + ": must be a map with type, instructions and criteria");
                continue;
            }

            Object type = question.get("type");
            if (!(type instanceof String typeName) || typeName.isBlank()) {
                problems.add(id + ": type is required");
                continue;
            }
            if (!(question.get("instructions") instanceof String instructions) || instructions.isBlank()) {
                problems.add(id + ": instructions are required");
            }

            switch (typeName) {
                case "choice" -> validateChoice(id, question.get("criteria"), problems);
                case "score" -> validateScore(id, question.get("criteria"), problems);
                default -> { }
            }
        }
        return problems;
    }

    private static void validateChoice(String id, Object criteria, List<String> problems) {
        if (!(criteria instanceof Map<?, ?> options)) {
            problems.add(id + ": a choice question needs criteria as a map of label to description, not an array");
            return;
        }
        if (options.size() < CHOICE_MIN || options.size() > CHOICE_MAX) {
            problems.add(id + ": a choice question needs " + CHOICE_MIN + " to " + CHOICE_MAX
                    + " labels, got " + options.size());
        }
        for (Map.Entry<?, ?> option : options.entrySet()) {
            if (!(option.getKey() instanceof String label) || label.isBlank()) {
                problems.add(id + ": choice labels must be non-blank text");
            } else if (!(option.getValue() instanceof String description) || description.isBlank()) {
                problems.add(id + ": the description for '" + label + "' must be non-blank text");
            }
        }
    }

    private static void validateScore(String id, Object criteria, List<String> problems) {
        if (!(criteria instanceof List<?> levels)) {
            problems.add(id + ": a score question needs criteria as an ordered array of level descriptions, not a map");
            return;
        }
        if (levels.size() < SCORE_MIN || levels.size() > SCORE_MAX) {
            problems.add(id + ": a score question needs " + SCORE_MIN + " to " + SCORE_MAX
                    + " levels, got " + levels.size());
        }
        for (int i = 0; i < levels.size(); i++) {
            if (!(levels.get(i) instanceof String level) || level.isBlank()) {
                problems.add(id + ": level " + i + " must be non-blank text");
            }
        }
    }
}
