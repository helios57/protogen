package io.github.helios57.protogen.compiler.linker;

import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.ProtoFile;

import java.util.List;
import java.util.Map;

/**
 * All parsed files after linking, plus the symbol table.
 *
 * @param files   the files, in the order they were given to the compiler
 * @param symbols fully qualified proto name to declaration
 */
public record Schema(List<ProtoFile> files, Map<String, Defs.TypeDef> symbols) {

    /** @return every distinct Java package the schema generates into */
    public List<String> javaPackages() {
        return files.stream().map(ProtoFile::javaPackage).distinct().sorted().toList();
    }

    /** @return the files that generate into {@code javaPackage} */
    public List<ProtoFile> filesIn(String javaPackage) {
        return files.stream().filter(f -> f.javaPackage().equals(javaPackage)).toList();
    }
}
