package dev.inkstone.core.search

/**
 * SPEC §8 / ROADMAP M7 — FTS5 over recognised handwriting and note titles.
 *
 * Empty in M0 by design. The module exists from the start so that it carries
 * the `:core` guardrails (platform-free, iOS-linkable) from its first line of
 * code rather than acquiring them retroactively at M7 — by which point a JVM-only
 * tokenizer dependency would already be load-bearing.
 *
 * The one design constraint recorded now: search must degrade to titles-only
 * when ML Kit's language model is unavailable (ROADMAP M7), so nothing here may
 * assume recognised text exists.
 */
public object SearchModule {
    public const val PLACEHOLDER_UNTIL: String = "M7"
}
