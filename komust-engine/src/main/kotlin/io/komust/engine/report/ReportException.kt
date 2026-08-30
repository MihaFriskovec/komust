package io.komust.engine.report

/**
 * Every way reading a persisted `report.json` / `survivors.json` can fail. All
 * are terminal — the same stance as the coverage package's
 * [io.komust.engine.coverage.CoveragePassException].
 */
public sealed class ReportException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The JSON was not parseable as a komust report (malformed, wrong shape). */
public class MalformedReportException(message: String, cause: Throwable? = null) :
    ReportException(message, cause)

/**
 * The file's `schemaVersion` is a **different major** than this build emits.
 * Within a major komust tolerates any later minor (additive-only), but a major
 * bump is a breaking change a reader must not guess past — the same "reject an
 * unrecognised version rather than guess" rule as `scope.json`
 * (`docs/scope-json.md`).
 */
public class UnsupportedSchemaVersionException(public val found: String, public val supported: String) :
    ReportException(
        "report schemaVersion '$found' has a different major version than this build supports ('$supported') — " +
            "a major bump is a breaking change; use a matching komust",
    )
