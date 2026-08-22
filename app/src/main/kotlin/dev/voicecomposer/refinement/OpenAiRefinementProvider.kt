package dev.voicecomposer.refinement

import dev.voicecomposer.core.ProcessingLocation
import dev.voicecomposer.core.ProviderCapabilities
import dev.voicecomposer.security.CredentialStore
import dev.voicecomposer.security.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional BYOK refinement through the OpenAI API.
 *
 * ## Billing honesty
 *
 * This uses the OpenAI *API*, billed per token against the key the user
 * supplies. It is not connected to, and cannot be paid for by, a ChatGPT Plus
 * or Pro subscription - those are separate products with separate billing. See
 * docs/FEASIBILITY.md. Nothing in this app can charge refinement to a ChatGPT
 * subscription, and the UI says so at the point the key is entered.
 *
 * ## Model selection
 *
 * The model id is a user-visible setting with a conservative default rather
 * than a hard-coded constant, because model availability changes and this
 * source tree could not reach OpenAI's documentation to confirm the current
 * catalogue at the time it was written. Settings offers a free-text field and
 * surfaces the API's own error if the id is wrong.
 *
 * NOTE: implemented but not tested against the live API - the build
 * environment had no network access to OpenAI. See docs/TEST_RESULTS.md.
 */
class OpenAiRefinementProvider(
    private val credentials: CredentialStore,
    private val modelId: String = DEFAULT_MODEL,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : RefinementProvider {

    override val capabilities = ProviderCapabilities(
        id = PROVIDER_ID,
        displayName = "OpenAI API (your key)",
        // Unambiguous: the draft is transmitted.
        processingLocation = ProcessingLocation.OFF_DEVICE,
        requiresNetwork = true,
        supportsStreaming = false,
    )

    override suspend fun isAvailable(): Boolean = credentials.has(CredentialStore.ALIAS_OPENAI)

    /** Handles any instruction; the deterministic provider is preferred first. */
    override fun supports(request: RefinementRequest): Boolean = true

    override suspend fun refine(request: RefinementRequest): RefinementResult =
        withContext(Dispatchers.IO) {
            val key = credentials.get(CredentialStore.ALIAS_OPENAI)
                ?: return@withContext RefinementResult.Failed(RefinementError.CredentialMissing)

            try {
                val payload = buildPayload(request)
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $key")
                }

                connection.outputStream.use { it.write(payload.toString().toByteArray()) }

                val status = connection.responseCode
                if (status !in 200..299) {
                    // The error body can echo the request, which contains the
                    // user's draft, so only the status code is recorded.
                    connection.errorStream?.close()
                    SafeLog.warn(TAG, "refine_http_error", mapOf("status" to status))
                    return@withContext RefinementResult.Failed(
                        RefinementError.Backend("http_$status"),
                    )
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val text = extractText(body)
                    ?: return@withContext RefinementResult.Failed(
                        RefinementError.Backend("unparseable_response"),
                    )

                RefinementResult.Success(text.trim(), PROVIDER_ID)
            } catch (e: java.net.UnknownHostException) {
                RefinementResult.Failed(RefinementError.NoNetwork)
            } catch (e: java.io.IOException) {
                SafeLog.failure(TAG, "refine_io_error", e)
                RefinementResult.Failed(RefinementError.NoNetwork)
            } catch (e: Exception) {
                SafeLog.failure(TAG, "refine_failed", e)
                RefinementResult.Failed(RefinementError.Backend("unexpected"))
            }
        }

    private fun buildPayload(request: RefinementRequest): JSONObject {
        val instruction = buildString {
            append(request.instruction.ifBlank { "Improve this text." })
            request.targetLanguage?.let { append(" Target language: $it.") }
        }

        return JSONObject().apply {
            put("model", modelId)
            put("temperature", 0.3)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", SYSTEM_PROMPT)
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "content",
                                "Instruction: $instruction\n\n---\n${request.text}",
                            )
                        },
                    )
                },
            )
        }
    }

    private fun extractText(body: String): String? = runCatching {
        JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }.getOrNull()

    companion object {
        private const val TAG = "OpenAiRefine"
        const val PROVIDER_ID = "openai-refinement"

        private const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"

        /** User-editable in Settings; see the class comment. */
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        private const val SYSTEM_PROMPT =
            "You rewrite the user's dictated text according to their instruction. " +
                "Return only the rewritten text with no preamble, no explanation, " +
                "no quotation marks and no commentary. Preserve the user's meaning " +
                "and any factual details. Do not add information the user did not provide."
    }
}
