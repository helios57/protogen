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

    /**
     * The Java packages this schema generates into; each gets its own codec.
     *
     * @return every distinct Java package, sorted
     */
    public List<String> javaPackages() {
        return files.stream().map(ProtoFile::javaPackage).distinct().sorted().toList();
    }

    /**
     * The files contributing to one Java package.
     *
     * @param javaPackage the package to select
     * @return the files that generate into it
     */
    public List<ProtoFile> filesIn(String javaPackage) {
        return files.stream().filter(f -> f.javaPackage().equals(javaPackage)).toList();
    }
}
