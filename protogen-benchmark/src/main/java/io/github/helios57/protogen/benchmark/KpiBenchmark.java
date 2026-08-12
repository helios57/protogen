package io.github.helios57.protogen.benchmark;

import com.java.proto.model.proto.KpiCollectionV1;
import com.java.proto.model.proto.KpiV1;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A realistic payload: a batch of OpenMetrics style KPIs, each with string maps for labels and metadata.
 * <p>
 * This is the only benchmark where protogen does strictly more work than protobuf-java, and deliberately
 * so: {@code KpiV1.key} carries a {@code @Pattern} annotation, so every protogen message - constructed or
 * parsed - runs the regex, while protobuf-java has no notion of the constraint and will happily produce
 * and accept an invalid metric name. The difference between this and {@link ScalarsBenchmark} is roughly
 * what schema-declared validation costs.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class KpiBenchmark {

    /** Number of KPIs in the batch. */
    @Param({"10", "100"})
    public int items;

    private KpiCollectionV1 mine;
    private com.java.proto.official.KpiCollectionV1 theirs;
    private byte[] encoded;

    @Setup
    public void setUp() {
        mine = protogenBatch(items);
        theirs = officialBatch(items);
        encoded = mine.toByteArray();
        if (encoded.length != theirs.toByteArray().length) {
            throw new IllegalStateException("the two encodings must be identical to be comparable");
        }
    }

    private static KpiCollectionV1 protogenBatch(int items) {
        List<KpiV1> kpis = new ArrayList<>(items);
        for (int i = 0; i < items; i++) {
            kpis.add(new KpiV1("jvm_memory_committed_bytes",
                    Map.of("area", "heap"), 42.1 + i, Map.of("TYPE", "seconds")));
        }
        return new KpiCollectionV1(kpis);
    }

    private static com.java.proto.official.KpiCollectionV1 officialBatch(int items) {
        com.java.proto.official.KpiCollectionV1.Builder b =
                com.java.proto.official.KpiCollectionV1.newBuilder();
        for (int i = 0; i < items; i++) {
            b.addItems(com.java.proto.official.KpiV1.newBuilder()
                    .setKey("jvm_memory_committed_bytes")
                    .putLabel("area", "heap")
                    .setValue(42.1 + i)
                    .putMeta("TYPE", "seconds")
                    .build());
        }
        return b.build();
    }

    @Benchmark
    public byte[] encode_protogen() {
        return mine.toByteArray();
    }

    @Benchmark
    public byte[] encode_protobufJava() {
        return theirs.toByteArray();
    }

    @Benchmark
    public byte[] buildAndEncode_protogen() {
        return protogenBatch(items).toByteArray();
    }

    @Benchmark
    public byte[] buildAndEncode_protobufJava() {
        return officialBatch(items).toByteArray();
    }

    /** Includes running the {@code @Pattern} regex on every key; protobuf-java does no such check. */
    @Benchmark
    public KpiCollectionV1 decode_protogen() {
        return KpiCollectionV1.parseFrom(encoded);
    }

    @Benchmark
    public com.java.proto.official.KpiCollectionV1 decode_protobufJava() throws Exception {
        return com.java.proto.official.KpiCollectionV1.parseFrom(encoded);
    }

    /**
     * Sizing on its own, which is what a message would pay at construction if the size were cached in it.
     * <p>
     * protobuf-java is reading a memoised field here rather than computing anything, so the two columns
     * measure different things on purpose: the question this answers is what eager sizing would add to
     * every parse and every constructor call, including the messages that are never serialized.
     */
    @Benchmark
    public int size_protogen() {
        return mine.protoSize();
    }

    @Benchmark
    public int size_protobufJava() {
        return theirs.getSerializedSize();
    }
}
