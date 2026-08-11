package io.github.helios57.protogen.benchmark;

import com.google.protobuf.ByteString;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import protogen.it.model.ScalarsV1;

import java.util.concurrent.TimeUnit;

/**
 * A flat message of every scalar type: the baseline codec comparison, with no nesting, no collections and
 * no validation in the way.
 * <p>
 * <strong>Reading the numbers.</strong> {@code encode} reuses one prepared message, which favours
 * protobuf-java: it memoises {@code getSerializedSize()} on the instance, so after the first call its
 * serialization is a single pass, while protogen - being an immutable record with nowhere to cache -
 * always sizes and then writes. {@code buildAndEncode} constructs a fresh message each time, which is what
 * most services actually do and what the memoisation cannot help with. Both are reported so the trade-off
 * is visible rather than hidden by whichever shape was chosen.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class ScalarsBenchmark {

    private ScalarsV1 mine;
    private protogen.it.official.ScalarsV1 theirs;
    private byte[] encoded;

    @Setup
    public void setUp() {
        mine = newProtogen();
        theirs = newOfficial();
        encoded = mine.toByteArray();
        if (encoded.length != theirs.toByteArray().length) {
            throw new IllegalStateException("the two encodings must be identical to be comparable");
        }
    }

    private static ScalarsV1 newProtogen() {
        return new ScalarsV1("a moderately sized string value", true, 42, 43L, 44, 45L, 46, 47L,
                48, 49L, 50, 51L, 1.5F, 2.5D, new byte[]{1, 2, 3, 4, 5, 6, 7, 8},
                "optional", 7, true, 0.5D, new byte[]{9}, "wide", 12345);
    }

    private static protogen.it.official.ScalarsV1 newOfficial() {
        return protogen.it.official.ScalarsV1.newBuilder()
                .setText("a moderately sized string value").setFlag(true)
                .setI32(42).setI64(43L).setU32(44).setU64(45L).setS32(46).setS64(47L)
                .setF32(48).setF64(49L).setSf32(50).setSf64(51L).setReal32(1.5F).setReal64(2.5D)
                .setBlob(ByteString.copyFrom(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}))
                .setOptionalText("optional").setOptionalNumber(7).setOptionalFlag(true)
                .setOptionalReal(0.5D).setOptionalBlob(ByteString.copyFrom(new byte[]{9}))
                .setWideTagText("wide").setWideTagNumber(12345)
                .build();
    }

    // ------------------------------------------------------- encode, reused

    @Benchmark
    public byte[] encode_protogen() {
        return mine.toByteArray();
    }

    @Benchmark
    public byte[] encode_protobufJava() {
        return theirs.toByteArray();
    }

    // --------------------------------------------------- build then encode

    @Benchmark
    public byte[] buildAndEncode_protogen() {
        return newProtogen().toByteArray();
    }

    @Benchmark
    public byte[] buildAndEncode_protobufJava() {
        return newOfficial().toByteArray();
    }

    // ------------------------------------------------------------- decode

    @Benchmark
    public ScalarsV1 decode_protogen() {
        return ScalarsV1.parseFrom(encoded);
    }

    @Benchmark
    public protogen.it.official.ScalarsV1 decode_protobufJava() throws Exception {
        return protogen.it.official.ScalarsV1.parseFrom(encoded);
    }

    // ---------------------------------------------------------- size only

    @Benchmark
    public int size_protogen() {
        return mine.protoSize();
    }

    @Benchmark
    public int size_protobufJava() {
        return theirs.getSerializedSize();
    }
}
