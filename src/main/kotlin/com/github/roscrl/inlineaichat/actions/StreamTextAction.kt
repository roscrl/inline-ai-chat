package com.github.roscrl.inlineaichat.actions

import com.github.roscrl.inlineaichat.notifications.NotificationUtil
import com.github.roscrl.inlineaichat.settings.InlineAIChatSettingsState
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlin.coroutines.coroutineContext

class StreamTextAction : AnAction() {
    companion object {
        private val AI_ICON = com.intellij.icons.AllIcons.Actions.Lightning
    }

    private class AIGutterIconRenderer(private val timestamp: String) : GutterIconRenderer() {
        override fun getIcon(): Icon = AI_ICON
        override fun equals(other: Any?): Boolean = other is AIGutterIconRenderer
        override fun hashCode(): Int = 0
        override fun getTooltipText(): String = "${InlineAIChatSettingsState.instance.selectedModel} Response ($timestamp)"
    }

    private val isStreaming = AtomicBoolean(false)
    private var lastActionTime = Instant.now()
    private val DEBOUNCE_MS = 500L // reduced to 500ms for better responsiveness
    private var rateLimitTask: Task.Backgroundable? = null
    private val isRateLimited = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MINUTES)
        .readTimeout(500, TimeUnit.MINUTES)
        .writeTimeout(500, TimeUnit.MINUTES)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val logger = Logger.getInstance(StreamTextAction::class.java)
    private val rateLimitLock = Object()

    override fun update(e: AnActionEvent) {
        val hasEditor = e.getData(CommonDataKeys.EDITOR) != null
        val notStreaming = !isStreaming.get()
        val noRateLimit = !isRateLimited.get()

        logger.debug("Action state: hasEditor=$hasEditor, notStreaming=$notStreaming, noRateLimit=$noRateLimit")

        // Only enable the action if we have an editor and are not streaming or rate limited
        e.presentation.isEnabled = hasEditor && notStreaming && noRateLimit
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            logger.warn("No project available")
            return
        }
        val editor = e.getData(CommonDataKeys.EDITOR) ?: run {
            logger.warn("No editor available")
            return
        }

        // Calculate start offset before streaming
        val startOffset = calculateStartOffset(editor)

        // Get selected text or whole document
        val context = editor.selectionModel.selectedText
            ?: editor.document.text

        // Set streaming flag immediately
        if (!isStreaming.compareAndSet(false, true)) {
            logger.warn("Action ignored: Race condition on streaming flag")
            return
        }

        logger.debug("Action triggered")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Streaming ${InlineAIChatSettingsState.instance.selectedModel}", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    runBlocking {
                        streamAIResponse(project, editor, startOffset, context, indicator)
                    }
                } catch (e: Exception) {
                    logger.error("Unhandled exception in streamAIResponse", e)
                    NotificationUtil.showError(
                        "Error",
                        "An error occurred while streaming the ${InlineAIChatSettingsState.instance.selectedModel} response: ${e.message}",
                        project
                    )
                } finally {
                    isStreaming.set(false)
                }
            }
        })
    }

    private suspend fun streamAIResponse(
        project: Project,
        editor: Editor,
        initialOffset: Int,
        context: String,
        indicator: ProgressIndicator
    ) {
        // Add gutter icon at the start
        val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        var rangeHighlighter: RangeHighlighter? = null

        ApplicationManager.getApplication().invokeAndWait {
            // Remove any existing AI gutter icon at the exact same offset
            val existingHighlighters = editor.markupModel.allHighlighters
            
            existingHighlighters.forEach { highlighter ->
                if (highlighter.startOffset == initialOffset) {
                    val renderer = highlighter.gutterIconRenderer
                    if (renderer is AIGutterIconRenderer) {
                        editor.markupModel.removeHighlighter(highlighter)
                    }
                }
            }

            // Add new gutter icon
            rangeHighlighter = editor.markupModel.addRangeHighlighter(
                initialOffset,
                initialOffset,
                HighlighterLayer.LAST,
                null,
                com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
            ).apply {
                gutterIconRenderer = AIGutterIconRenderer(timestamp)
            }
        }

        try {
            val settings = InlineAIChatSettingsState.instance
            logger.debug("Starting ${settings.selectedModel} response stream: contextLength=${context.length}")

            if (settings.openRouterApiKey.isEmpty()) {
                logger.warn("OpenRouter API key not configured")
                WriteCommandAction.runWriteCommandAction(project) {
                    editor.document.insertString(
                        initialOffset,
                        "Please configure your OpenRouter API key in Settings -> Tools -> Inline AI Chat Settings\n"
                    )
                }
                NotificationUtil.showWarning(
                    title = "Configuration Required",
                    content = "Please configure your OpenRouter API key in Settings -> Tools -> Inline AI Chat Settings",
                    project = project
                )
                isStreaming.set(false)
                return
            }

            val requestBody = try {
                val body = JSONObject().apply {
                    put("model", settings.selectedModel)
                    put(
                        "messages", listOf(
                            mapOf("role" to "system", "content" to settings.systemPrompt),
                            mapOf("role" to "user", "content" to context)
                        )
                    )
                    put("stream", true)
                }.toString()
                logger.debug("Created request body for model: ${settings.selectedModel}")
                body.toRequestBody(JSON)
            } catch (e: JSONException) {
                logger.error("Failed to create request body", e)
                throw IOException("Failed to create request: ${e.message}")
            }

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${settings.openRouterApiKey}")
                .post(requestBody)
                .build()

            logger.debug("Sending request to OpenRouter API")
            val call = client.newCall(request)
            // Attach a cancellation handler to cancel the OkHttp call if the coroutine is cancelled.
            coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    logger.debug("Cancelling OkHttp call due to coroutine cancellation")
                    call.cancel()
                }
            }

            // Check for cancellation before making the request
            if (indicator.isCanceled) {
                logger.debug("Request cancelled before execution")
                throw CancellationException()
            }

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error details available"
                    logger.error("API request failed: ${response.code} - $errorBody")

                    if (response.code == 429) {
                        // Extract retry time from response if available
                        val retrySeconds = try {
                            val error = JSONObject(errorBody).getJSONObject("error")
                            val metadata = error.optJSONObject("metadata")
                            val rawError = metadata?.optString("raw") ?: ""
                            if (rawError.contains("Retry after")) {
                                rawError.substringAfter("Retry after")
                                    .substringBefore("seconds")
                                    .trim()
                                    .toIntOrNull()?.plus(2) ?: 62  // Add 2 second buffer
                            } else {
                                62  // Default 60 seconds plus buffer
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to parse retry time from response, using default", e)
                            62  // Default 60 seconds plus buffer
                        }

                        logger.warn("Rate limit hit, starting countdown for $retrySeconds seconds (including buffer)")
                        startRateLimitCountdown(project, retrySeconds)
                        return
                    }

                    throw IOException("API request failed (${response.code}): $errorBody")
                }

                logger.debug("Received successful response from API")
                response.body?.source()?.use { source ->
                    // Ensure we have two newlines before starting
                    var currentOffset = initialOffset
                    val text = editor.document.text
                    var existingNewlines = 0
                    var pos = text.length - 1
                    while (pos >= initialOffset && text[pos].isWhitespace()) {
                        if (text[pos] == '\n') existingNewlines++
                        pos--
                    }

                    var buffer = ""
                    var totalCharsWritten = 0

                    try {
                        while (!source.exhausted() && isStreaming.get()) {
                            // Check for cancellation
                            if (indicator.isCanceled) {
                                logger.debug("Request cancelled during streaming")
                                isStreaming.set(false)
                                cleanupRateLimit()
                                throw CancellationException()
                            }

                            val line = source.readUtf8Line() ?: continue
                            if (!line.startsWith("data: ")) continue

                            val data = line.substringAfter("data: ")
                            if (data == "[DONE]") {
                                logger.debug("Received [DONE] signal after writing $totalCharsWritten characters")
                                break
                            }

                            try {
                                val json = JSONObject(data)
                                // Handle rate limit error if present
                                if (handleRateLimit(json, totalCharsWritten, currentOffset, project, editor)) {
                                    return
                                }

                                val content = when {
                                    json.has("choices") -> {
                                        logger.debug("Using OpenAI format")
                                        json.getJSONArray("choices")
                                            .getJSONObject(0)
                                            .getJSONObject("delta")
                                            .optString("content")
                                    }

                                    json.has("response") -> {
                                        logger.debug("Using DeepSeek format")
                                        json.getString("response")
                                    }

                                    json.has("text") -> {
                                        logger.debug("Using fallback text format")
                                        json.getString("text")
                                    }

                                    json.has("content") -> {
                                        logger.debug("Using fallback content format")
                                        json.getString("content")
                                    }

                                    else -> {
                                        val keys = json.keys().asSequence().toList()
                                        logger.warn("Unexpected response format. Available keys: $keys, Full response: $data")
                                        if (totalCharsWritten == 0) {
                                            WriteCommandAction.runWriteCommandAction(project) {
                                                editor.document.insertString(
                                                    currentOffset,
                                                    "\nError: Unexpected response format from model." +
                                                            "\nAvailable response fields: $keys" +
                                                            "\nPlease report this issue with the model name: ${settings.selectedModel}\n"
                                                )
                                            }
                                            throw IOException("Unexpected response format from model. Available fields: $keys")
                                        }
                                        null
                                    }
                                }

                                if (!content.isNullOrEmpty()) {
                                    buffer += content
                                    // Write in chunks for performance and check cancellation
                                    if (buffer.length >= 10 || source.exhausted()) {
                                        // Check for cancellation before writing
                                        if (indicator.isCanceled) {
                                            logger.debug("Request cancelled before writing buffer")
                                            throw CancellationException()
                                        }

                                        logger.debug("Writing buffer of length ${buffer.length} at offset $currentOffset")
                                        currentOffset = writeToEditor(project, editor, currentOffset, buffer)
                                        totalCharsWritten += buffer.length
                                        if (totalCharsWritten > 0) {
                                            updateProgressIndicator(totalCharsWritten, indicator)
                                        }
                                        buffer = ""
                                    }
                                }
                            } catch (e: JSONException) {
                                val errorMsg = e.message ?: "Unknown JSON parsing error"
                                logger.warn("Failed to parse streaming response: $errorMsg, Raw data: $data")
                                if (totalCharsWritten == 0) {
                                    cleanupNewlines(project, editor, initialOffset, initialOffset)
                                }
                                throw IOException("Failed to parse model response: $errorMsg")
                            }
                        }

                        // Write any remaining content if not cancelled
                        if (buffer.isNotEmpty() && !indicator.isCanceled) {
                            logger.debug("Writing final buffer of length ${buffer.length} at offset $currentOffset")
                            currentOffset = writeToEditor(project, editor, currentOffset, buffer)
                            totalCharsWritten += buffer.length
                        }

                        logger.debug("Streaming completed successfully. Total characters written: $totalCharsWritten")
                    } finally {
                        if (totalCharsWritten == 0) {
                            logger.debug("Cleaning up placeholder due to no content written")
                            cleanupNewlines(project, editor, initialOffset, editor.document.textLength)
                        }
                    }
                } ?: run {
                    logger.error("No response body received")
                    throw IOException("No response received from API")
                }
            }
        } catch (e: Exception) {
            // Remove the gutter icon if there's an error
            ApplicationManager.getApplication().invokeAndWait {
                rangeHighlighter?.let { editor.markupModel.removeHighlighter(it) }
            }
            throw e
        }
    }

    /**
     * Updates the progress indicator based on the total characters written.
     */
    private fun updateProgressIndicator(totalCharsWritten: Int, indicator: ProgressIndicator) {
        indicator.isIndeterminate = false
        indicator.text = "Streaming ${InlineAIChatSettingsState.instance.selectedModel}"
        indicator.fraction = (totalCharsWritten.toDouble() / 2000.0).coerceAtMost(0.95)
    }

    /**
     * Checks and handles rate limit errors in the JSON response.
     * Returns true if rate limit was detected and handled.
     */
    private fun handleRateLimit(
        json: JSONObject,
        totalCharsWritten: Int,
        currentOffset: Int,
        project: Project,
        editor: Editor
    ): Boolean {
        if (json.has("error")) {
            val error = json.getJSONObject("error")
            if (error.getInt("code") == 429) {
                val metadata = error.optJSONObject("metadata")
                val rawError = metadata?.optString("raw") ?: ""
                logger.warn("Rate limit response: code=429, metadata=$metadata, rawError=$rawError")
                val retrySeconds = if (rawError.contains("Retry after")) {
                    val extracted = rawError.substringAfter("Retry after")
                        .substringBefore("seconds")
                        .trim()
                        .toIntOrNull()
                    logger.info("Extracted retry time: $extracted seconds from: $rawError")
                    extracted ?: 60
                } else {
                    logger.info("No retry time found in response, using default 60 seconds")
                    60
                }
                logger.warn("Rate limit exceeded. Retry after $retrySeconds seconds")
                startRateLimitCountdown(project, retrySeconds)
                if (totalCharsWritten == 0) {
                    logger.debug("Cleaning up newlines due to rate limit")
                    cleanupNewlines(project, editor, currentOffset, currentOffset)
                }
                isStreaming.set(false)
                logger.info("Rate limit handling complete, streaming stopped")
                return true
            }
        }
        return false
    }

    /**
     * Writes text to the editor document at the specified offset.
     * Returns the new offset after insertion.
     */
    private fun writeToEditor(project: Project, editor: Editor, offset: Int, text: String): Int {
        var newOffset = offset
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                logger.debug("Writing to editor at offset $offset: length=${text.length}")
                editor.document.insertString(newOffset, text)
                newOffset += text.length
            }
        } catch (e: Exception) {
            logger.error("Failed to write to editor", e)
            throw IOException("Failed to write to editor: ${e.message}")
        }
        return newOffset
    }

    /**
     * Cleans up extra newlines in the editor if no content was written.
     */
    private fun cleanupNewlines(project: Project, editor: Editor, initialOffset: Int, currentOffset: Int) {
        logger.debug("Cleaning up newlines: initialOffset=$initialOffset, currentOffset=$currentOffset")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            try {
                // Perform all document length checks and modifications atomically
                val documentLength = document.textLength
                if (initialOffset >= documentLength) {
                    logger.debug("Initial offset $initialOffset is beyond document length $documentLength, skipping cleanup")
                    return@runWriteCommandAction
                }

                val safeEndOffset = minOf(currentOffset, documentLength)
                if (safeEndOffset > initialOffset) {
                    logger.debug("Deleting newlines from $initialOffset to $safeEndOffset")
                    document.deleteString(initialOffset, safeEndOffset)
                } else {
                    logger.debug("No cleanup needed: safeEndOffset=$safeEndOffset <= initialOffset=$initialOffset")
                }
            } catch (e: Exception) {
                logger.error("Error during newline cleanup", e)
                // Don't rethrow - we don't want to break the entire action for a cleanup failure
            }
        }
    }

    /**
     * Starts a rate limit countdown using a background task.
     */
    private fun startRateLimitCountdown(project: Project, seconds: Int) {
        synchronized(rateLimitLock) {
            if (!isRateLimited.compareAndSet(false, true)) {
                logger.warn("RATE LIMIT: Countdown already running, ignoring new request")
                return
            }

            logger.warn("RATE LIMIT: Starting countdown for $seconds seconds")

            val newTask = object : Task.Backgroundable(project, "Rate Limit", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        var remainingSeconds = seconds
                        indicator.isIndeterminate = false
                        val startTime = System.currentTimeMillis()

                        while (remainingSeconds > 0 && !indicator.isCanceled) {
                            val minutes = remainingSeconds / 60
                            val secs = remainingSeconds % 60
                            val timeDisplay = if (minutes > 0) {
                                String.format("%d:%02d", minutes, secs)
                            } else {
                                "${secs}s"
                            }
                            val progressFraction = 1.0 - (remainingSeconds.toDouble() / seconds.toDouble())
                            indicator.fraction = progressFraction
                            indicator.text = "Rate limit: $timeDisplay remaining"
                            
                            // Break into smaller sleep intervals to be more responsive to cancellation
                            for (i in 0..9) {
                                if (indicator.isCanceled) break
                                Thread.sleep(100)
                            }
                            
                            remainingSeconds--
                        }

                        if (!indicator.isCanceled) {
                            val elapsedTime = System.currentTimeMillis() - startTime
                            val remainingTime = (seconds * 1000L) - elapsedTime
                            if (remainingTime > 0) {
                                logger.warn("RATE LIMIT: Waiting additional ${remainingTime}ms to ensure full duration")
                                Thread.sleep(remainingTime)
                            }

                            logger.warn("RATE LIMIT: Countdown completed")
                            lastActionTime = Instant.now().minusMillis(DEBOUNCE_MS + 100)
                            logger.warn("RATE LIMIT: Reset lastActionTime to allow immediate use")

                            Thread.sleep(150)

                            // Only auto-retry if not cancelled
                            ApplicationManager.getApplication().invokeLater {
                                if (rateLimitTask === this && !project.isDisposed) {
                                    logger.info("RATE LIMIT: Automatically retrying request")
                                    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                                    if (editor != null) {
                                        // Use ActionManager to properly execute the action
                                        val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                                        val action = actionManager.getAction("com.github.roscrl.inlineaichat.actions.StreamText")
                                        
                                        cleanupRateLimit()
                                        
                                        // Use the proper API method for executing actions
                                        actionManager.tryToExecute(
                                            action,
                                            null,  // InputEvent
                                            editor.component,  // Use editor component instead of null
                                            "InlineAIChat",  // Place 
                                            true  // now
                                        )
                                    } else {
                                        logger.warn("Could not retry request: No editor available")
                                        cleanupRateLimit()
                                    }
                                } else {
                                    logger.warn("RATE LIMIT: Task no longer active or project disposed, skipping retry")
                                    cleanupRateLimit()
                                }
                            }
                        } else {
                            logger.warn("RATE LIMIT: Countdown cancelled by user")
                            cleanupRateLimit()
                        }
                    } catch (e: Exception) {
                        logger.error("RATE LIMIT: Error during countdown", e)
                        cleanupRateLimit()
                    }
                }

                override fun onCancel() {
                    super.onCancel()
                    logger.warn("RATE LIMIT: Countdown cancelled")
                    cleanupRateLimit()
                }
            }

            rateLimitTask = newTask
            ProgressManager.getInstance().run(newTask)
        }
    }

    /**
     * Safely cleans up rate limit state, ensuring proper synchronization
     */
    private fun cleanupRateLimit() {
        rateLimitTask = null
        isRateLimited.set(false)
    }

    // Updated implementation for calculateStartOffset:
    // Simply returns the document's end offset so that newline insertion is managed exclusively in streamAIResponse.
    private fun calculateStartOffset(editor: Editor): Int {
        val document = editor.document
        val text = document.text

        // Count existing newlines at end of file
        var existingNewlines = 0
        var pos = text.length - 1
        while (pos >= 0 && text[pos] == '\n') {
            existingNewlines++
            pos--
        }

        // Add newlines if needed to ensure 4 at the end
        val desiredNewlines = 4
        val newlinesToAdd = (desiredNewlines - existingNewlines).coerceAtLeast(0)
        if (newlinesToAdd > 0) {
            WriteCommandAction.runWriteCommandAction(editor.project) {
                document.insertString(document.textLength, "\n".repeat(newlinesToAdd))
            }
        }

        // Position cursor just before the last newline
        return document.textLength - 2
    }
}