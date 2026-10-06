package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.tools.feature.R
import kotlinx.coroutines.CancellationException

/**
 * Runs the tools a model asked for (#470), and turns what happened — including
 * what went wrong — into text the model can read.
 *
 * Nothing here ever throws. A tool that fails is not an error in the run: the
 * model asked a question, the answer is "that did not work", and a model told
 * so can apologise, try a different query, or answer from what it knows. A
 * thrown exception would instead kill the whole generation and show the user a
 * network error for a page they never asked for.
 *
 * Both surfaces share this: the AI panel's actions and the AI chat.
 */
class AiToolRunner(
    private val context: Context,
    private val settings: KeyboardSettings,
) {

    /**
     * Runs one call. Blocking; call on an IO dispatcher.
     *
     * The tools the model may call are decided by [AiTools.enabled] before the
     * request is built, so an unknown name here means the model invented one —
     * answered rather than ignored, because a model that gets silence repeats
     * itself until the round limit.
     */
    fun run(call: AiToolCall): AiToolResult = runCatching {
        when (call.name) {
            AiTools.WEB_SEARCH -> search(call)
            AiTools.WEB_FETCH -> fetch(call)
            else -> refused(
                call,
                context.getString(R.string.ftools_ai_tool_unknown, call.name),
                context.getString(R.string.ftools_ai_tool_reason_unknown),
            )
        }
    }.getOrElse { failure ->
        // Everything else becomes an answer the model can act on, but a
        // cancelled run is not a failed tool: swallowing it here would
        // keep a retired generation going round the loop.
        if (failure is CancellationException) throw failure
        val reason = ToolHttp.friendlyMessage(context, failure)
        refused(call, context.getString(R.string.ftools_ai_tool_failed, reason), reason)
    }

    /**
     * A call that did not work: [content] tells the model what to do about it,
     * [reason] tells the user what happened. Two texts because they are for
     * two readers — "answer from what you already know" is not something to
     * show a person.
     */
    private fun refused(call: AiToolCall, content: String, reason: String) =
        AiToolResult(call, content, error = reason)

    private fun missing(call: AiToolCall, argument: String) = refused(
        call,
        context.getString(R.string.ftools_ai_tool_missing_argument, argument),
        context.getString(R.string.ftools_ai_tool_reason_missing, argument),
    )

    private fun search(call: AiToolCall): AiToolResult {
        val query = call.argument("query") ?: return missing(call, "query")
        val backend = ToolApiKeys.searchBackend(settings) ?: return refused(
            call,
            context.getString(R.string.ftools_ai_tool_no_search),
            context.getString(R.string.ftools_ai_tool_reason_no_search),
        )
        val safe = settings.webSearch.safe
        val page = when (backend) {
            SearchBackend.SEARXNG -> SearxClient.webSearch(
                query, settings.selfHosted.searxUrl, SEARCH_RESULTS, safe,
                source = NetSource.AI_TOOLS,
            )
            SearchBackend.TAVILY -> TavilySearchClient.webSearch(
                query,
                ToolApiKeys.tavily(settings),
                SEARCH_RESULTS,
                safe,
                advanced = settings.webSearch.tavilyAdvanced,
                // Asked for whether or not the panel shows answer boxes: this
                // one is read by a model, not drawn, and it is the cheapest
                // summary of the page Tavily will ever give us.
                answer = true,
                source = NetSource.AI_TOOLS,
            )
            SearchBackend.BRAVE -> WebSearchPage(
                BraveSearchClient.webSearch(
                    query, ToolApiKeys.brave(settings), SEARCH_RESULTS, safe,
                    source = NetSource.AI_TOOLS,
                ),
            )
        }
        return AiToolResult(
            call = call,
            content = formatSearch(page, context.getString(R.string.ftools_ai_tool_no_results)),
            sources = page.results.map { AiToolSource(it.title.trim(), it.url) },
        )
    }

    private fun fetch(call: AiToolCall): AiToolResult {
        val url = call.argument("url") ?: return missing(call, "url")
        // Checked here as well as in the client, so the user is told plainly
        // rather than shown the instruction the model gets.
        val target = WebFetchClient.normalize(url) ?: return refused(
            call,
            context.getString(R.string.ftools_web_fetch_bad_url),
            context.getString(R.string.ftools_ai_tool_reason_bad_url),
        )
        val page = WebFetchClient.fetch(target)
        return AiToolResult(
            call = call,
            content = formatPage(page, url, context.getString(R.string.ftools_ai_tool_truncated)),
            sources = listOf(AiToolSource(page.title.trim(), target)),
        )
    }

    companion object {

        /**
         * The tools one run may offer, from the settings and from what is
         * actually set up.
         *
         * Search drops out when no backend is configured rather than being
         * offered and failing: a tool the model can only ever be refused by
         * still costs its description in the prompt of every run, and a small
         * model handed one tends to keep trying it.
         */
        fun enabled(settings: KeyboardSettings): List<AiToolSpec> = AiTools.enabled(
            search = settings.ai.toolWebSearch && ToolApiKeys.searchBackend(settings) != null,
            fetch = settings.ai.toolWebFetch,
        )

        /**
         * Results per search handed to the model: fewer than the panel shows a
         * person. Every one of them is replayed into every later round of the
         * same run, so a page of twenty would crowd out the answer.
         */
        private const val SEARCH_RESULTS = 5

        /**
         * Search results as numbered blocks of title, address and snippet.
         *
         * The address is included on purpose, even though it costs tokens: it
         * is what lets the model follow up with `web_fetch`, and what lets it
         * cite a source instead of asserting one.
         */
        internal fun formatSearch(page: WebSearchPage, emptyText: String): String {
            if (page.results.isEmpty() && page.answer.isNullOrBlank()) return emptyText
            return buildString {
                page.answer?.takeIf { it.isNotBlank() }?.let {
                    append("Summary: ").append(it.trim()).append("\n\n")
                }
                page.results.forEachIndexed { index, result ->
                    if (index > 0) append("\n\n")
                    append(index + 1).append(". ").append(result.title.trim()).append('\n')
                    append(result.url).append('\n')
                    append(result.snippet.trim())
                }
            }.trim()
        }

        /** One fetched page, labelled so the model knows what it is reading. */
        internal fun formatPage(page: WebFetchClient.Page, url: String, truncatedNote: String): String =
            buildString {
                if (page.title.isNotBlank()) append(page.title).append('\n')
                append(url).append("\n\n")
                append(page.text)
                if (page.truncated) append("\n\n").append(truncatedNote)
            }.trim()
    }
}
