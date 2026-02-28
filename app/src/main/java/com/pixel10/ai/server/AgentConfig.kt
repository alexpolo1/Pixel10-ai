package com.pixel10.ai.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Default agent configuration for the Pixel10 AI coding agent.
 *
 * Designed for Gemini Nano on the Tensor G5 chip — a small, fast,
 * fully on-device model. The system prompt and tool set are tuned to
 * work within the model's context window by keeping every turn focused
 * and minimal. No token is wasted.
 *
 * Tool use flow (OpenAI-compatible):
 *   1. Client sends messages (+ tools from this config)
 *   2. Server returns finish_reason=tool_calls with the tool to invoke
 *   3. Client executes the tool locally, appends result as role=tool message
 *   4. Client sends updated conversation back → repeat until task_done
 */
object AgentConfig {

    // ── System Prompt ──────────────────────────────────────────────────────────
    //
    // Target: ≤ 700 tokens. Every token here costs context on every turn.
    // Written to get the most out of a small on-device model:
    //  - Numbered rules (easy to follow for small models)
    //  - Explicit workflow stages (reduces hallucination / aimless tool calls)
    //  - Hard size limits on reads/writes (prevents context overflow)

    const val SYSTEM_PROMPT = """You are a precise coding agent running on a Pixel 10's Tensor G5 chip.

## Constraints
- One tool call per turn. Wait for the result before calling another.
- Your text reply must be 1-3 sentences max. Let tools do the work.
- Never output file contents in text — use read_file and write_file.
- Never change more than 3 files per task. If more are needed, stop and ask.

## Workflow — follow in order every time
1. EXPLORE  : list_dir to map the structure. read_file with start_line/end_line to read only what is relevant (max 120 lines per read). search_code to locate symbols.
2. PLAN     : State in one sentence what you will change and why.
3. CHANGE   : Use patch_file to replace exact text (preferred). Use write_file only for new files or full rewrites under 80 lines.
4. VERIFY   : run_command to build or test after every change. Fix errors before continuing.
5. DONE     : Call task_done with a one-paragraph summary of every file changed.

## Rules
- Always read a file before modifying it.
- When reading large files, use start_line/end_line — never load the whole file.
- Use search_code before reading to find the exact lines you need.
- patch_file is preferred over write_file: specify the exact text to replace.
- If a command fails, read the error, fix the cause, retry once. If it fails again, call task_done with the error and what you tried."""

    // ── Tool Definitions ───────────────────────────────────────────────────────
    //
    // 7 tools covering the full coding agent surface.
    // Descriptions are kept short — they repeat on every request turn.

    val DEFAULT_TOOLS: List<Tool> = listOf(

        tool(
            name = "read_file",
            description = "Read a file. Use start_line/end_line to read a section (max 120 lines). Always prefer sections over full files.",
            properties = mapOf(
                "path"       to strProp("Absolute or workspace-relative file path"),
                "start_line" to intProp("First line to read, 1-indexed (optional)"),
                "end_line"   to intProp("Last line to read, 1-indexed (optional)")
            ),
            required = listOf("path")
        ),

        tool(
            name = "write_file",
            description = "Create a new file or fully overwrite an existing one. Use only for new files or complete rewrites under 80 lines. Prefer patch_file for edits.",
            properties = mapOf(
                "path"    to strProp("File path to write"),
                "content" to strProp("Full file content to write")
            ),
            required = listOf("path", "content")
        ),

        tool(
            name = "patch_file",
            description = "Replace an exact string inside a file. Preferred for edits — avoids rewriting the whole file. old_str must match exactly including whitespace.",
            properties = mapOf(
                "path"    to strProp("File path to patch"),
                "old_str" to strProp("Exact text to find and replace (must match exactly)"),
                "new_str" to strProp("Replacement text")
            ),
            required = listOf("path", "old_str", "new_str")
        ),

        tool(
            name = "list_dir",
            description = "List files and directories at a path. Use depth=1 for a flat listing, depth=2 to include one level of subdirectories.",
            properties = mapOf(
                "path"  to strProp("Directory path to list"),
                "depth" to intProp("Max depth: 1 (flat) or 2 (with subdirs). Default 1.")
            ),
            required = listOf("path")
        ),

        tool(
            name = "search_code",
            description = "Search for a regex pattern in files. Returns matching lines with file path and line number. Use this before read_file to find exactly which lines to read.",
            properties = mapOf(
                "pattern" to strProp("Regex pattern to search for"),
                "path"    to strProp("Directory or file to search in (default: workspace root)"),
                "include" to strProp("Glob filter, e.g. '*.kt' or '*.py' (optional)")
            ),
            required = listOf("pattern")
        ),

        tool(
            name = "run_command",
            description = "Run a shell command and return stdout+stderr. Use for build, test, lint, install. Keep commands short and targeted.",
            properties = mapOf(
                "command" to strProp("Shell command to execute"),
                "cwd"     to strProp("Working directory (optional, defaults to workspace root)")
            ),
            required = listOf("command")
        ),

        tool(
            name = "task_done",
            description = "Signal that the task is fully complete. Call this as the final action — never leave a task without calling it.",
            properties = mapOf(
                "summary"       to strProp("One paragraph describing what was changed and why"),
                "files_changed" to strProp("Comma-separated list of files that were modified or created")
            ),
            required = listOf("summary")
        )
    )

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList()
    ): Tool {
        val params = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                properties.forEach { (k, v) -> add(k, v) }
            })
            if (required.isNotEmpty()) {
                add("required", JsonArray().apply { required.forEach { add(it) } })
            }
        }
        return Tool(function = ToolFunction(name = name, description = description, parameters = params))
    }

    private fun strProp(description: String) = JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
    }

    private fun intProp(description: String) = JsonObject().apply {
        addProperty("type", "integer")
        addProperty("description", description)
    }
}
