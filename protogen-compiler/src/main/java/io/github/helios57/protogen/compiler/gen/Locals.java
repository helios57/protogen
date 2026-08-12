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
    /** The loop index and bound for an indexed walk over a repeated field. */
    final String index;
    final String count;
    final String entry;
    final String key;
    final String value;
    final String limit;
    /** A second limit, for a submessage read inside a map entry, which has pushed one of its own. */
    final String valueLimit;
    final String entryTag;
    final String len;
    final String payload;
    final String nested;
    /** The plan of nested sizes, computed while sizing and read back while writing. */
    final String sizes;
    /** The slot in that plan a size is written back into once its subtree has been measured. */
    final String slot;
    /** A second slot, for the entry a map with message values reserves before descending. */
    final String valueSlot;
    final String entrySize;
    final String valueSize;
    final String candidate;
    final String other;
    final String result;
    /** The trailing component holding unknown fields, when preservation is enabled. */
    final String unknown;

    private final Set<String> taken = new HashSet<>();

    /**
     * Allocates a name nothing else in the record uses.
     *
     * @param preferred the name to use if it is free
     * @return that name, or an underscore-suffixed variant that is
     */
    String free(String preferred) {
        return free(preferred, taken);
    }

    Locals(List<Defs.FieldDef> fields) {
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
        this.index = free("i", taken);
        this.count = free("count", taken);
        this.entry = free("e", taken);
        this.key = free("key", taken);
        this.value = free("value", taken);
        this.limit = free("limit", taken);
        this.valueLimit = free("valueLimit", taken);
        this.entryTag = free("entryTag", taken);
        this.len = free("len", taken);
        this.payload = free("payload", taken);
        this.nested = free("nested", taken);
        this.sizes = free("sizes", taken);
        this.slot = free("slot", taken);
        this.valueSlot = free("valueSlot", taken);
        this.entrySize = free("entrySize", taken);
        this.valueSize = free("valueSize", taken);
        this.candidate = free("o", taken);
        this.other = free("other", taken);
        this.result = free("result", taken);
        this.unknown = free("unknownFields", taken);
    }

    private static String free(String preferred, Set<String> taken) {
        String name = preferred;
        while (!taken.add(name)) {
            name = name + "_";
        }
        return name;
    }
}
