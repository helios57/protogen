package io.github.helios57.protogen.compiler.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation constraints declared as {@code @Annotation} lines in a field's leading comment.
 * <p>
 * The vocabulary matches the JSON-Schema flavoured annotations already common in real-world {@code .proto}
 * files, so the same comment drives both the AsyncAPI documentation pipeline and the generated validation.
 *
 * @param minLength        {@code @MinLength n} - minimum {@code String} length
 * @param maxLength        {@code @MaxLength n} - maximum {@code String} length
 * @param minimum          {@code @Minimum n} / {@code @Min n} - inclusive lower bound
 * @param maximum          {@code @Maximum n} / {@code @Max n} - inclusive upper bound
 * @param exclusiveMinimum {@code @ExclusiveMinimum n} - exclusive lower bound
 * @param exclusiveMaximum {@code @ExclusiveMaximum n} - exclusive upper bound
 * @param multipleOf       {@code @MultipleOf n} - value must be an exact multiple
 * @param minItems         {@code @MinItems n} - minimum size of a repeated field
 * @param maxItems         {@code @MaxItems n} - maximum size of a repeated field
 * @param pattern          {@code @Pattern regex} - regular expression the value must match
 * @param required         {@code @Required} - the field must be present / non-default
 * @param examples         {@code @Example v} - documentation only, emitted into the Javadoc
 * @param rootNode         {@code @RootNode} - documentation only, marks a top-level API message
 */
public record Constraints(Integer minLength,
                          Integer maxLength,
                          BigDecimal minimum,
                          BigDecimal maximum,
                          BigDecimal exclusiveMinimum,
                          BigDecimal exclusiveMaximum,
                          BigDecimal multipleOf,
                          Integer minItems,
                          Integer maxItems,
                          String pattern,
                          boolean required,
                          List<String> examples,
                          boolean rootNode) {

    private static final Pattern ANNOTATION = Pattern.compile("^\\s*@(\\w+)\\s*(.*)$");

    public static final Constraints NONE =
            new Constraints(null, null, null, null, null, null, null, null, null, null, false, List.of(), false);

    /** @return {@code true} if anything here produces a runtime check */
    public boolean hasValidation() {
        return minLength != null || maxLength != null || minimum != null || maximum != null
                || exclusiveMinimum != null || exclusiveMaximum != null || multipleOf != null
                || minItems != null || maxItems != null || pattern != null || required;
    }

    /** Parses the annotation lines out of a leading comment block. Unknown annotations are ignored. */
    public static Constraints parse(String comment) {
        if (comment == null || comment.isBlank()) {
            return NONE;
        }
        Integer minLength = null;
        Integer maxLength = null;
        BigDecimal minimum = null;
        BigDecimal maximum = null;
        BigDecimal exclusiveMinimum = null;
        BigDecimal exclusiveMaximum = null;
        BigDecimal multipleOf = null;
        Integer minItems = null;
        Integer maxItems = null;
        String pattern = null;
        boolean required = false;
        boolean rootNode = false;
        List<String> examples = new ArrayList<>();

        for (String line : comment.split("\n")) {
            Matcher m = ANNOTATION.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2).strip();
            switch (name) {
                case "minlength" -> minLength = parseInt(value);
                case "maxlength" -> maxLength = parseInt(value);
                case "minimum", "min" -> minimum = parseDecimal(value);
                case "maximum", "max" -> maximum = parseDecimal(value);
                case "exclusiveminimum" -> exclusiveMinimum = parseDecimal(value);
                case "exclusivemaximum" -> exclusiveMaximum = parseDecimal(value);
                case "multipleof" -> multipleOf = parseDecimal(value);
                case "minitems" -> minItems = parseInt(value);
                case "maxitems" -> maxItems = parseInt(value);
                case "pattern" -> pattern = value.isEmpty() ? null : value;
                case "required" -> required = true;
                case "rootnode" -> rootNode = true;
                case "example" -> {
                    if (!value.isEmpty()) {
                        examples.add(value);
                    }
                }
                default -> {
                    // unknown annotation - documentation only, ignored here
                }
            }
        }
        return new Constraints(minLength, maxLength, minimum, maximum, exclusiveMinimum, exclusiveMaximum,
                multipleOf, minItems, maxItems, pattern, required, List.copyOf(examples), rootNode);
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.valueOf(firstToken(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(firstToken(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstToken(String value) {
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }
}
