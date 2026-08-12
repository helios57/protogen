package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The {@code @Example} and {@code @RootNode} annotations reach a documentation pipeline through a JSON
 * sidecar in {@code META-INF/protogen/}, rather than through the generated runtime.
 * <p>
 * That keeps documentation out of the hot path - the records stay free of annotations and constants that
 * exist only to be read by tooling - while still making the schema's intent machine-readable without
 * re-parsing the {@code .proto} or reflecting over the classes.
 * <p>
 * Well-formedness is checked with {@link Json}, a sixty-line reader written for these tests, because
 * protogen-it must acquire no dependencies - not even in test scope. Substring assertions alone would not
 * do: a missing comma passes every one of them.
 */
class SchemaMetadataTest {

    private static final Path METADATA = Path.of("target", "generated-resources", "protogen",
            "META-INF", "protogen");

    private static String read(String name) throws IOException {
        Path file = METADATA.resolve(name);
        assertThat(file).as("metadata sidecar for " + name).exists();
        return Files.readString(file);
    }

    @Test
    void everySidecarIsWellFormedJson() throws IOException {
        String[] names = METADATA.toFile().list();
        assertThat(names).isNotEmpty();

        for (String name : names) {
            String json = Files.readString(METADATA.resolve(name));
            assertThatCode(() -> Json.parse(json))
                    .as("%s must be valid JSON", name)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theParsedDocumentHasTheExpectedShape() throws IOException {
        Map<String, Object> root = Json.parseObject(read("kpiV1.json"));

        assertThat(root).containsKeys("schemaVersion", "file", "protoPackage", "javaPackage",
                "javaMultipleFiles", "enums", "messages");

        List<?> messages = (List<?>) root.get("messages");
        Map<?, ?> kpi = (Map<?, ?>) messages.get(0);
        assertThat(kpi.get("name")).isEqualTo("KpiV1");
        assertThat(kpi.get("rootNode")).isEqualTo(Boolean.TRUE);

        List<?> fields = (List<?>) kpi.get("fields");
        Map<String, Object> key = asObject(fields.get(0));
        assertThat(key.get("name")).isEqualTo("key");
        assertThat(key.get("number")).isEqualTo(1.0D);
        assertThat((List<Object>) key.get("examples"))
                .containsExactly((Object) "jvm_memory_committed_bytes");
        assertThat(asObject(key.get("constraints")))
                .containsEntry("pattern", "^[a-zA-Z_:][a-zA-Z0-9_:]*$");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void aSidecarIsWrittenForEveryProtoFile() {
        assertThat(METADATA).isDirectory();
        assertThat(METADATA.toFile().list())
                .contains("validation.json", "kpiV1.json", "nested.json", "scalars.json",
                        "enums.json", "oneofs.json", "timestamps.json", "wrapped.json");
    }

    @Test
    void theSidecarNamesTheFileAndItsJavaMapping() throws IOException {
        String json = read("kpiV1.json");

        assertThat(json)
                .contains("\"file\": \"kpiV1.proto\"")
                .contains("\"protoPackage\": \"com.java.proto\"")
                .contains("\"javaPackage\": \"com.java.proto.model.proto\"")
                .contains("\"javaType\": \"com.java.proto.model.proto.KpiV1\"");
    }

    @Test
    void rootNodeIsRecorded() throws IOException {
        assertThat(read("kpiV1.json")).contains("\"rootNode\": true");
        // a message without the annotation is explicitly marked false, not left out
        assertThat(read("scalars.json")).contains("\"rootNode\": false");
    }

    @Test
    void examplesAreRecordedPerField() throws IOException {
        String json = read("kpiV1.json");

        assertThat(json).contains("\"jvm_memory_committed_bytes\"");
        assertThat(json).contains("{\\\"area\\\":\\\"heap\\\"}");
    }

    @Test
    void constraintsAreRecordedPerField() throws IOException {
        String json = read("validation.json");

        assertThat(json)
                .contains("\"minLength\": 3")
                .contains("\"maxLength\": 10")
                .contains("\"minimum\": 1")
                .contains("\"maximum\": 100")
                .contains("\"exclusiveMinimum\": 0")
                .contains("\"multipleOf\": 5")
                .contains("\"minItems\": 1")
                .contains("\"maxItems\": 5")
                .contains("\"required\": true");
    }

    @Test
    void thePatternIsRecordedWithItsRegexIntact() throws IOException {
        assertThat(read("kpiV1.json"))
                .contains("\"pattern\": \"^[a-zA-Z_:][a-zA-Z0-9_:]*$\"");
    }

    @Test
    void documentationProseIsSeparatedFromTheAnnotations() throws IOException {
        String json = read("kpiV1.json");

        assertThat(json).contains("Unique key to identify metric");
        // the annotation lines are structured data now, not prose
        assertThat(json).doesNotContain("@Pattern").doesNotContain("@Example");
    }

    @Test
    void fieldNumbersTypesAndLabelsAreRecorded() throws IOException {
        String json = read("nested.json");

        assertThat(json)
                .contains("\"number\": 1")
                .contains("\"type\": \"protogen.it.StageEnumV1\"")
                .contains("\"label\": \"repeated\"")
                .contains("\"label\": \"optional\"")
                .contains("\"type\": \"map<string, string>\"");
    }

    @Test
    void wellKnownFieldsAreIdentifiableAsSuch() throws IOException {
        assertThat(read("timestamps.json"))
                .contains("\"kind\": \"well_known\"")
                .contains("\"type\": \"google.protobuf.Timestamp\"");
        assertThat(read("wellknown.json"))
                .contains("\"type\": \"google.protobuf.Duration\"")
                .contains("\"type\": \"google.protobuf.StringValue\"")
                // the ones with no JDK counterpart are ordinary message references
                .contains("\"type\": \"google.protobuf.Struct\"");
    }

    @Test
    void nestedAndWrappedTypesAreRecorded() throws IOException {
        assertThat(read("nested.json"))
                .contains("\"nestedMessages\"")
                .contains("\"javaType\": \"protogen.it.model.NodeV1.CoordinatesV1\"");
        assertThat(read("wrapped.json"))
                .contains("\"javaOuterClassName\": \"Wrapped\"")
                .contains("\"javaType\": \"protogen.it.model.Wrapped.OuterApiEnumV1\"");
    }

    @Test
    void theSidecarIsPackagedIntoTheArtifact() {
        // added as a project resource, so it lands on the classpath for whatever consumes it
        assertThat(getClass().getResource("/META-INF/protogen/kpiV1.json"))
                .as("the sidecar must be a classpath resource, not just a file in target")
                .isNotNull();
    }
}
