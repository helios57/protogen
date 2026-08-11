package io.github.helios57.protogen.compiler.gen;

import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.Names;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Names for the locals and parameters the generator introduces, chosen so they cannot collide with the
 * message's own components.
 * <p>
 * A schema is free to declare fields called {@code key}, {@code value}, {@code size} or {@code target}, and
 * a generated method that also used those names would either fail to compile or - worse - silently shadow
 * the component. Each name here falls back to an underscore-suffixed variant until it is free.
 */
final class Locals {

    final String target;
    final String offset;
    final String size;
    final String tag;
    final String reader;
    final String data;
    final String length;
    final String out;
    final String element;
    final String entry;
    final String key;
    final String value;
    final String limit;
    final String entryTag;
    final String len;
    final String payload;
    final String nested;
    final String entrySize;
    final String valueSize;
    final String candidate;
    final String other;
    final String result;

    Locals(List<Defs.FieldDef> fields) {
        Set<String> taken = new HashSet<>();
        for (Defs.FieldDef f : fields) {
            taken.add(Names.fieldName(f.name()));
        }
        this.target = free("target", taken);
        this.offset = free("offset", taken);
        this.size = free("size", taken);
        this.tag = free("tag", taken);
        this.reader = free("r", taken);
        this.data = free("data", taken);
        this.length = free("length", taken);
        this.out = free("out", taken);
        this.element = free("v", taken);
        this.entry = free("e", taken);
        this.key = free("key", taken);
        this.value = free("value", taken);
        this.limit = free("limit", taken);
        this.entryTag = free("entryTag", taken);
        this.len = free("len", taken);
        this.payload = free("payload", taken);
        this.nested = free("nested", taken);
        this.entrySize = free("entrySize", taken);
        this.valueSize = free("valueSize", taken);
        this.candidate = free("o", taken);
        this.other = free("other", taken);
        this.result = free("result", taken);
    }

    private static String free(String preferred, Set<String> taken) {
        String name = preferred;
        while (!taken.add(name)) {
            name = name + "_";
        }
        return name;
    }
}
