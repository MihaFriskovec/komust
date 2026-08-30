package io.komust.scope

/**
 * Thrown when the Mutation Scope cannot be resolved at all — `git` is missing,
 * the target directory is not a work tree, or the default branch cannot be
 * determined. An *empty* changeset is not an error: it resolves to
 * [MutationScope.EMPTY].
 */
class ScopeResolutionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
