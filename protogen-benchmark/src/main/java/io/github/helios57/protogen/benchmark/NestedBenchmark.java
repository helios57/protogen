package io.github.helios57.protogen.benchmark;

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
import protogen.it.model.NodeV1;
import protogen.it.model.StageEnumV1;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A tree of nested messages, with repeated fields and a map at every level.
 * <p>
 * This is where protogen's lack of size memoisation costs the most: a record cannot cache
 * {@code protoSize()}, so serializing a tree of depth <em>d</em> walks it <em>d</em> times, while
 * protobuf-java memoises each node's size on the instance. The {@code depth} parameter makes that cost
 * visible - if the gap widens sharply with depth, PLAN.md's open question about memoisation has its answer.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class NestedBenchmark {

    /** Tree depth. Each level holds two children, so the node count grows as 2^depth. */
    @Param({"1", "3", "5"})
    public int depth;

    private NodeV1 mine;
    private protogen.it.official.NodeV1 theirs;
    private byte[] encoded;

    @Setup
    public void setUp() {
        mine = protogenTree(depth);
        theirs = officialTree(depth);
        encoded = mine.toByteArray();
        if (encoded.length != theirs.toByteArray().length) {
            throw new IllegalStateException("the two encodings must be identical to be comparable");
        }
    }

    private static NodeV1 protogenTree(int depth) {
        List<NodeV1> children = new ArrayList<>();
        if (depth > 0) {
            children.add(protogenTree(depth - 1));
            children.add(protogenTree(depth - 1));
        }
        return new NodeV1("node-" + depth, StageEnumV1.PROD, "beta", children,
                List.of(1, 2, 3, 4, 5), List.of("alpha", "beta"),
                Map.of("primary", "https://a.example"),
                new NodeV1.CoordinatesV1(46.95, 7.44), NodeV1.KindV1.BROKER, Map.of(), Map.of());
    }

    private static protogen.it.official.NodeV1 officialTree(int depth) {
        protogen.it.official.NodeV1.Builder b = protogen.it.official.NodeV1.newBuilder()
                .setName("node-" + depth)
                .setStage(protogen.it.official.StageEnumV1.PROD)
                .setStageSuffix("beta")
                .addAllPorts(List.of(1, 2, 3, 4, 5))
                .addAllTags(List.of("alpha", "beta"))
                .putEndpoints("primary", "https://a.example")
                .setLocation(protogen.it.official.NodeV1.CoordinatesV1.newBuilder()
                        .setLatitude(46.95).setLongitude(7.44).build())
                .setKind(protogen.it.official.NodeV1.KindV1.BROKER);
        if (depth > 0) {
            b.addChildren(officialTree(depth - 1));
            b.addChildren(officialTree(depth - 1));
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
        return protogenTree(depth).toByteArray();
    }

    @Benchmark
    public byte[] buildAndEncode_protobufJava() {
        return officialTree(depth).toByteArray();
    }

    @Benchmark
    public NodeV1 decode_protogen() {
        return NodeV1.parseFrom(encoded);
    }

    @Benchmark
    public protogen.it.official.NodeV1 decode_protobufJava() throws Exception {
        return protogen.it.official.NodeV1.parseFrom(encoded);
    }

    /** Sizing on its own: what caching the size in the record would cost at construction. */
    @Benchmark
    public int size_protogen() {
        return mine.protoSize();
    }

    @Benchmark
    public int size_protobufJava() {
        return theirs.getSerializedSize();
    }
}
