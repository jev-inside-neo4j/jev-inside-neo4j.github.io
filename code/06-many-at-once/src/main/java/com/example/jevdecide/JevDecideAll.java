package com.example.jevdecide;

import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * jev.decideAll(items, questions, options) - many decisions in one call.
 * A thin wrapper: the work happens in BatchRunner so it can be tested without Neo4j.
 */
public class JevDecideAll {

    private static final BatchRunner RUNNER = new BatchRunner(JevDecide.CLIENT);

    /** One output row. Neo4j reads the public fields as the YIELD columns. */
    public static class Result {
        public Object id;
        public Map<String, Object> answers;
        public String error_message;
        public Map<String, Object> metadata;

        public Result(BatchRunner.Row row) {
            this.id = row.id();
            this.answers = row.answers();
            this.error_message = row.errorMessage();
            this.metadata = row.metadata();
        }
    }

    @Procedure(name = "jev.decideAll", mode = Mode.READ)
    @Description("Ask Jev the same typed questions about many items. items is a list of {id, state}. "
            + "Returns one row per item, in input order: id, answers, error_message, metadata. "
            + "Option concurrency (1 to 16, default 4) sets how many calls run at once. "
            + "A failing row reports error_message and never stops the others.")
    public Stream<Result> decideAll(
            @Name("items") List<Map<String, Object>> items,
            @Name("questions") Map<String, Object> questions,
            @Name(value = "options", defaultValue = "{}") Map<String, Object> options) {
        return RUNNER.run(items, questions, options).stream().map(Result::new);
    }
}
