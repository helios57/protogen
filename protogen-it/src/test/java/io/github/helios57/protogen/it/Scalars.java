package io.github.helios57.protogen.it;

import protogen.it.model.ScalarsV1;

/**
 * Test-side builder for {@link ScalarsV1}.
 * <p>
 * protogen deliberately generates records and nothing else, so constructing a twenty-component message in a
 * test needs a helper. Keeping it here rather than in the generator keeps the generated code minimal.
 */
public final class Scalars {

    String text = "";
    boolean flag;
    int i32;
    long i64;
    int u32;
    long u64;
    int s32;
    long s64;
    int f32;
    long f64;
    int sf32;
    long sf64;
    float real32;
    double real64;
    byte[] blob = new byte[0];
    String optionalText;
    Integer optionalNumber;
    Boolean optionalFlag;
    Double optionalReal;
    byte[] optionalBlob;
    String wideTagText = "";
    int wideTagNumber;

    public static Scalars empty() {
        return new Scalars();
    }

    public Scalars text(String value) {
        this.text = value;
        return this;
    }

    public Scalars flag(boolean value) {
        this.flag = value;
        return this;
    }

    public Scalars i32(int value) {
        this.i32 = value;
        return this;
    }

    public Scalars i64(long value) {
        this.i64 = value;
        return this;
    }

    public Scalars u32(int value) {
        this.u32 = value;
        return this;
    }

    public Scalars u64(long value) {
        this.u64 = value;
        return this;
    }

    public Scalars s32(int value) {
        this.s32 = value;
        return this;
    }

    public Scalars s64(long value) {
        this.s64 = value;
        return this;
    }

    public Scalars f32(int value) {
        this.f32 = value;
        return this;
    }

    public Scalars f64(long value) {
        this.f64 = value;
        return this;
    }

    public Scalars sf32(int value) {
        this.sf32 = value;
        return this;
    }

    public Scalars sf64(long value) {
        this.sf64 = value;
        return this;
    }

    public Scalars real32(float value) {
        this.real32 = value;
        return this;
    }

    public Scalars real64(double value) {
        this.real64 = value;
        return this;
    }

    public Scalars blob(byte[] value) {
        this.blob = value;
        return this;
    }

    public Scalars optionalText(String value) {
        this.optionalText = value;
        return this;
    }

    public Scalars optionalNumber(Integer value) {
        this.optionalNumber = value;
        return this;
    }

    public Scalars optionalFlag(Boolean value) {
        this.optionalFlag = value;
        return this;
    }

    public Scalars optionalReal(Double value) {
        this.optionalReal = value;
        return this;
    }

    public Scalars optionalBlob(byte[] value) {
        this.optionalBlob = value;
        return this;
    }

    public Scalars wideTagText(String value) {
        this.wideTagText = value;
        return this;
    }

    public Scalars wideTagNumber(int value) {
        this.wideTagNumber = value;
        return this;
    }

    public ScalarsV1 build() {
        return new ScalarsV1(text, flag, i32, i64, u32, u64, s32, s64, f32, f64, sf32, sf64, real32,
                real64, blob, optionalText, optionalNumber, optionalFlag, optionalReal, optionalBlob,
                wideTagText, wideTagNumber);
    }
}
