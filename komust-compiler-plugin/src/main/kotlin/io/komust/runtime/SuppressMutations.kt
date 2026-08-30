package io.komust.runtime

/**
 * The user-facing suppression hatch (ADR-0001, #36).
 *
 * Applied to a declaration — or to a whole file with `@file:SuppressMutations` —
 * it tells `komust-compiler-plugin` to weave **no** mutants anywhere inside that
 * declaration, even at sites the built-in skip-list does not cover. It is the
 * escape valve for the handful of places a project knows a mutant is
 * equivalent or junk but komust cannot see it.
 *
 * The comment form `// komust:ignore` (on a site's line or the line above) does
 * the same job at single-line granularity without an import.
 *
 * `BINARY` retention keeps the annotation out of runtime reflection while still
 * letting a separate mutation compilation (which recompiles the same sources)
 * observe it; it is never needed at run time.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.BINARY)
public annotation class SuppressMutations
