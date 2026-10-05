package com.wasimaster.wmkeyboard.core.tools

/**
 * Drives one AI run that may call tools, round after round, until the model
 * stops asking for them and writes an answer (#470).
 *
 * Generic over how a round is actually produced, because the two ways differ
 * completely below this line: a cloud or self-hosted provider streams over
 * HTTP through [AiClient], and an on-device model is a blocking native call
 * with no HTTP anywhere near it. Both come back as a [AiClient.Completion],
 * so both can be driven by the same loop — which matters, since the rules
 * about rounds, about what the user is shown while a tool runs, and about
 * where the sentinel fallback applies should not be written down twice.
 */
object AiToolLoop {

    /** One round of generation, however it is produced. */
    fun interface Round {
        /**
         * @param tools what the model may call this round. Empty on the final
         *   round, which is what forces an answer instead of another call.
         * @param onPartial the round's own text so far, not the whole run's.
         */
        fun run(
            system: String,
            turns: List<AiClient.ChatTurn>,
            tools: List<AiToolSpec>,
            onPartial: (String) -> Unit,
        ): AiClient.Completion
    }

    /** Runs one call the model asked for. Must not throw; see `AiToolRunner`. */
    fun interface Executor {
        fun run(call: AiToolCall): AiToolResult
    }

    /**
     * Runs [turns] to an answer, calling tools on the way.
     *
     * [native] says whether the round's provider has real function calling. It
     * does not change the loop, only where the calls are read from: the
     * provider's own fields, or [AiToolProtocol]'s sentinel pulled back out of
     * the text. A model on the sentinel path is also told the protocol, which
     * is why [system] is rewritten for it.
     *
     * [maxRounds] bounds how many times tools may be called before the model
     * has to answer with what it has. A model that keeps asking is not
     * stopped with an error: the last round is simply run with no tools
     * offered, so the user gets an answer rather than a failure.
     */
    @Suppress("LongParameterList")
    fun run(
        system: String,
        turns: List<AiClient.ChatTurn>,
        tools: List<AiToolSpec>,
        native: Boolean,
        executor: Executor,
        maxRounds: Int = AiTools.DEFAULT_MAX_ROUNDS,
        onPhase: (AiPhase) -> Unit = {},
        onPartial: (String) -> Unit = {},
        onToolCall: (AiToolCall) -> Unit = {},
        isActive: () -> Boolean = { true },
        round: Round,
    ): AiClient.Completion {
        if (tools.isEmpty()) {
            return round.run(system, turns, emptyList(), onPartial)
        }
        val prompt = if (native) system else system + AiToolProtocol.instructions(tools)
        val conversation = ArrayList(turns)
        // Text from rounds already finished. A model that says "let me look
        // that up" and then searches has written something the user should
        // keep seeing while the search runs.
        val answered = StringBuilder()
        var used = 0
        while (true) {
            val offered = if (used < maxRounds) tools else emptyList()
            val prefix = answered.toString()
            // What a partial of this round is appended to. The separator
            // belongs here and not in [answered], so that the text streamed
            // mid-run and the text returned at the end are the same string.
            val streamed = if (prefix.isEmpty()) "" else prefix + "\n\n"
            val completion = round.run(
                // The final round is not told about tools at all, so a model
                // on the sentinel path cannot spend it writing another one.
                if (offered.isEmpty() && !native) system else prompt,
                conversation,
                offered,
            ) { partial -> onPartial(streamed + visible(partial, native)) }

            val text = if (native) completion.text else AiToolProtocol.stripped(completion.text)
            val calls = if (native) completion.toolCalls else AiToolProtocol.parseAll(completion.text)
            if (offered.isEmpty() || calls.isEmpty() || !isActive()) {
                return completion.copy(text = join(prefix, text), toolCalls = emptyList())
            }
            append(answered, text)

            onPhase(AiPhase.USING_TOOL)
            val results = calls.map { call ->
                onToolCall(call)
                executor.run(call)
            }
            record(conversation, completion, calls, results, native)
            used++
        }
    }

    /**
     * What may be shown of a partial. On the sentinel path a half-written
     * `<tool_c` must be held back rather than flashed into the panel, which is
     * exactly what a filter fed but not flushed does.
     */
    private fun visible(partial: String, native: Boolean): String =
        if (native) partial else AiToolProtocol.Filter().feed(partial)

    /** Adds this round's request and its answers to the conversation. */
    private fun record(
        conversation: MutableList<AiClient.ChatTurn>,
        completion: AiClient.Completion,
        calls: List<AiToolCall>,
        results: List<AiToolResult>,
        native: Boolean,
    ) {
        if (native) {
            conversation.add(
                AiClient.ChatTurn(AiClient.ChatRole.ASSISTANT, completion.text, toolCalls = calls),
            )
            for (result in results) {
                conversation.add(
                    AiClient.ChatTurn(AiClient.ChatRole.TOOL, result.content, toolResult = result),
                )
            }
            return
        }
        // The sentinel path has no tool role to use, so the exchange is two
        // ordinary messages: the model's own words played back verbatim —
        // sentinel included, so it can see what it asked for — and the answers
        // as the user message the protocol told it to expect.
        conversation.add(AiClient.ChatTurn(AiClient.ChatRole.ASSISTANT, completion.text))
        conversation.add(
            AiClient.ChatTurn(
                AiClient.ChatRole.USER,
                results.joinToString("\n\n") { AiToolProtocol.resultMessage(it) },
            ),
        )
    }

    private fun append(buffer: StringBuilder, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (buffer.isNotEmpty()) buffer.append("\n\n")
        buffer.append(trimmed)
    }

    private fun join(prefix: String, text: String): String {
        if (prefix.isBlank()) return text
        if (text.isBlank()) return prefix
        return prefix.trimEnd() + "\n\n" + text.trimStart()
    }
}
