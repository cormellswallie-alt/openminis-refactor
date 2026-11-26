package com.openminis.app.agent.jobs

import com.openminis.app.agent.SubAgentAccessMode

/**
 * Claude Code / Codex CLI-style specialized sub-agent behavioral contracts and
 * reusable preset templates.
 */
object SubAgentPresets {

    data class Template(
        val name: String,
        val description: String,
        val instructions: String,
        val defaultAccessMode: SubAgentAccessMode,
    )

    /**
     * Built-in role templates mirroring Claude Code (`Explore`, `Plan`, `Verify`)
     * and Codex CLI review workers. Surfaced for quick creation or prompt guidance
     * without altering `SubAgentRoster.normalize`'s 1-built-in iOS wire contract.
     */
    val TEMPLATES: List<Template> = listOf(
        Template(
            name = "Explore",
            description = "Fast read-only codebase researcher: maps symbols, finds files, traces call paths, and returns concise cited findings without editing anything.",
            instructions = "Stay strictly read-only. Batch file_search, repo_map, and file_read calls in parallel. Cite exact Linux file paths and line numbers for every finding.",
            defaultAccessMode = SubAgentAccessMode.READ_ONLY,
        ),
        Template(
            name = "Plan",
            description = "Read-only software architect: inspects dependencies, weighs trade-offs, and produces a concrete step-by-step implementation blueprint before code changes.",
            instructions = "Produce a crisp architectural plan: (1) target files to touch, (2) data/control flow changes, (3) edge cases & regressions to avoid, (4) verification tests. Never modify files.",
            defaultAccessMode = SubAgentAccessMode.PLAN,
        ),
        Template(
            name = "Verifier",
            description = "Independent test & regression verifier: runs builds/tests/linters and audits diffs objectively without modifying source code.",
            instructions = "Verify independently. Run actual commands and report verbatim exit codes and failing lines. Never claim tests passed unless you ran them.",
            defaultAccessMode = SubAgentAccessMode.VERIFY,
        ),
    )

    /**
     * Role-specific system prompt addendum injected into [HelperRunner.identitySection]
     * only when a non-default [SubAgentAccessMode] is selected (keeps `WORKSPACE_WRITE`
     * output byte-identical to existing iOS parity tests).
     */
    fun roleGuidance(mode: SubAgentAccessMode): String = when (mode) {
        SubAgentAccessMode.WORKSPACE_WRITE -> ""
        SubAgentAccessMode.READ_ONLY ->
            "Access mode: READ-ONLY EXPLORATION (Claude Code Explore parity).\n" +
                "- File writes/edits and mutating shell commands are blocked by the host.\n" +
                "- Batch parallel `repo_map`, `file_search`, and `file_read` calls to survey fast.\n" +
                "- Return a dense, structured report citing exact file paths, line ranges, and key symbols.\n\n"
        SubAgentAccessMode.PLAN ->
            "Access mode: READ-ONLY ARCHITECTURAL PLANNER (Claude Code Plan parity).\n" +
                "- File writes/edits and mutating shell commands are blocked by the host.\n" +
                "- Inspect the relevant modules first, then return an actionable implementation blueprint covering: impacted files, exact interface/logic changes, edge cases, and verification plan.\n\n"
        SubAgentAccessMode.VERIFY ->
            "Access mode: INDEPENDENT VERIFIER (Codex / Claude Code verification parity).\n" +
                "- `file_write` and `file_edit` are disabled so you cannot silently alter the code under review.\n" +
                "- Run concrete checks (tests, linters, diff inspection) and report objective evidence with exact exit codes, failing assertions, and file:line citations.\n\n"
    }
}
