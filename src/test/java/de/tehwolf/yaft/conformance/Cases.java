package de.tehwolf.yaft.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the conformance case files.
 *
 * <p>The cases are fetched by {@code scripts/fetch-conformance.sh} from the
 * version pinned in {@code conformance.lock}; Gradle runs it before every test
 * run and passes the directory in {@code yaft.conformance.dir}. They are not
 * checked in: the lock file plus its checksum is what makes a suite bump a
 * reviewable one-line diff.
 *
 * <p>Cases stay the plain {@code Map}/{@code List} tree Jackson produces, which
 * is also exactly what the library's mapping code consumes.
 */
final class Cases {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The case-file format versions this adapter implements. A file in any
     * other format may carry a field this adapter never reads, which would
     * leave a rule silently unenforced, so it is rejected instead.
     */
    private static final Map<String, Integer> FORMATS = Map.of("evaluation", 1, "decorator", 1, "mapping", 4);

    private Cases() {}

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> load(String suite) {
        String dir = System.getProperty("yaft.conformance.dir");
        if (dir == null) {
            throw new IllegalStateException("yaft.conformance.dir is not set; run the tests through Gradle");
        }
        Path path = Path.of(dir, "cases", suite + ".json");
        if (!Files.exists(path)) {
            throw new IllegalStateException("Conformance cases missing at " + path
                    + ". Run scripts/fetch-conformance.sh; ./gradlew test does this itself.");
        }

        Map<String, Object> file = JSON.readValue(path.toFile(), Map.class);
        if (!suite.equals(file.get("suite"))) {
            throw new IllegalStateException(path + " declares suite \"" + file.get("suite") + "\", expected \"" + suite + "\"");
        }
        if (!FORMATS.get(suite).equals(file.get("version"))) {
            throw new IllegalStateException(path + " is format version " + file.get("version") + ", but this adapter"
                    + " implements version " + FORMATS.get(suite) + ". Extend the adapter to the new format.");
        }
        List<Map<String, Object>> cases = (List<Map<String, Object>>) file.get("cases");
        if (cases == null || cases.isEmpty()) {
            throw new IllegalStateException(path + " contains no cases");
        }
        return cases;
    }

    /** Names a case after its rules, so a failure points at the spec without opening the file. */
    @SuppressWarnings("unchecked")
    static String title(Map<String, Object> c) {
        return c.get("name") + " " + (List<String>) c.get("rules");
    }

    /**
     * Rejects a value this adapter does not know.
     *
     * <p>A case whose {@code target}, {@code toggle} or {@code expected} the
     * adapter has never seen is a rule nothing enforces here. Skipping it would
     * leave the suite green while a requirement goes unchecked.
     */
    static AssertionError unsupported(String kind, Object value, Object caseName) {
        return new AssertionError("Case \"" + caseName + "\" uses " + kind + " \"" + value
                + "\", which this adapter does not implement. The conformance suite has gained a case this port"
                + " does not cover yet -- extend the adapter rather than skipping the case.");
    }
}
