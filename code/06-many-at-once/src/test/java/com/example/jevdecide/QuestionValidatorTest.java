package com.example.jevdecide;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionValidatorTest {

    private static Map<String, Object> choice(Object criteria) {
        Map<String, Object> q = new HashMap<>();
        q.put("type", "choice");
        q.put("instructions", "Pick one");
        q.put("criteria", criteria);
        return q;
    }

    private static Map<String, Object> score(Object criteria) {
        Map<String, Object> q = new HashMap<>();
        q.put("type", "score");
        q.put("instructions", "Rate it");
        q.put("criteria", criteria);
        return q;
    }

    private static Map<String, Object> one(String id, Map<String, Object> question) {
        Map<String, Object> questions = new HashMap<>();
        questions.put(id, question);
        return questions;
    }

    private static Map<String, Object> labels(int count) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            map.put("label" + i, "description " + i);
        }
        return map;
    }

    private static List<Object> levels(int count) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add("level " + i);
        }
        return list;
    }

    @Test
    void validChoiceAndScoreProduceNoProblems() {
        Map<String, Object> questions = new HashMap<>();
        questions.put("decision", choice(Map.of("Flag", "Fraud", "Pass", "Legitimate")));
        questions.put("rating", score(List.of("Low", "Medium", "High")));
        assertTrue(QuestionValidator.validate(questions).isEmpty());
    }

    @Test
    void nullAndEmptyQuestionsAreRejected() {
        assertFalse(QuestionValidator.validate(null).isEmpty());
        assertFalse(QuestionValidator.validate(Map.of()).isEmpty());
    }

    @Test
    void choiceWithArrayCriteriaIsRejected() {
        List<String> problems = QuestionValidator.validate(one("d", choice(List.of("a", "b"))));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("map"));
    }

    @Test
    void scoreWithMapCriteriaIsRejected() {
        List<String> problems = QuestionValidator.validate(one("s", score(Map.of("a", "b"))));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("array"));
    }

    @Test
    void choiceLabelCountBoundaries() {
        assertFalse(QuestionValidator.validate(one("d", choice(labels(0)))).isEmpty());
        assertTrue(QuestionValidator.validate(one("d", choice(labels(1)))).isEmpty());
        assertTrue(QuestionValidator.validate(one("d", choice(labels(255)))).isEmpty());
        assertFalse(QuestionValidator.validate(one("d", choice(labels(256)))).isEmpty());
    }

    @Test
    void scoreLevelCountBoundaries() {
        assertFalse(QuestionValidator.validate(one("s", score(levels(1)))).isEmpty());
        assertTrue(QuestionValidator.validate(one("s", score(levels(2)))).isEmpty());
        assertTrue(QuestionValidator.validate(one("s", score(levels(10)))).isEmpty());
        assertFalse(QuestionValidator.validate(one("s", score(levels(11)))).isEmpty());
    }

    @Test
    void missingInstructionsAreReported() {
        Map<String, Object> q = choice(Map.of("A", "a"));
        q.remove("instructions");
        List<String> problems = QuestionValidator.validate(one("d", q));
        assertTrue(problems.stream().anyMatch(p -> p.contains("instructions")));
    }

    @Test
    void missingTypeIsReported() {
        Map<String, Object> q = new HashMap<>();
        q.put("instructions", "Pick one");
        List<String> problems = QuestionValidator.validate(one("d", q));
        assertTrue(problems.stream().anyMatch(p -> p.contains("type")));
    }

    @Test
    void blankLabelDescriptionIsReported() {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("Flag", " ");
        assertFalse(QuestionValidator.validate(one("d", choice(criteria))).isEmpty());
    }

    @Test
    void otherQuestionTypesPassThroughWhenTheyHaveInstructions() {
        Map<String, Object> q = new HashMap<>();
        q.put("type", "some_other_type");
        q.put("instructions", "Anything");
        assertTrue(QuestionValidator.validate(one("x", q)).isEmpty());
    }

    @Test
    void aQuestionThatIsNotAMapIsReported() {
        Map<String, Object> questions = new HashMap<>();
        questions.put("d", "not a map");
        assertFalse(QuestionValidator.validate(questions).isEmpty());
    }
}
