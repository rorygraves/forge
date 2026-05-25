package io.forge.agents

/** §7.10(a) — Codex has no `--system-prompt` / `--system-prompt-file` flag, so the adapter concatenates the
  * system-prompt file into the user prompt before invoking `codex exec`.
  */
object CodexPrompt:

  /** Read the system-prompt file and prepend its contents to the user prompt, separated by a blank line.
    *
    * The shipped prompt files at `~/.forge/prompts/<phase>.codex.md` are self-describing — they already contain their
    * own `## System` header — so this is straight concatenation; the adapter does not inject the header itself.
    */
  def withSystemBlock(systemPromptPath: os.Path, userPrompt: String): String =
    val system = os.read(systemPromptPath).stripLineEnd
    val user = userPrompt.stripLineEnd
    if system.isEmpty then user
    else if user.isEmpty then system
    else s"$system\n\n$user"
