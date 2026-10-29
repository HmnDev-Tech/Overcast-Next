package com.sameerasw.overcast.weather.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OnDeviceAi {
    private val client: GenerativeModel by lazy { Generation.getClient() }

    @Volatile private var supported: Boolean? = null

    fun isSupportedCached(): Boolean? = supported

    suspend fun isSupported(): Boolean = supported ?: withContext(Dispatchers.IO) {
        val result = try {
            when (client.checkStatus()) {
                FeatureStatus.AVAILABLE, FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> true
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        supported = result
        result
    }

    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            ensureModel()
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = 0.6f
                maxOutputTokens = 256
            }
            val response = client.generateContent(request)
            response.candidates.firstOrNull()?.text?.trim()?.takeIf { it.isNotBlank() }
                ?: throw AiException(AiException.Reason.BAD_RESPONSE, response.candidates.firstOrNull()?.finishReason?.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException(AiException.Reason.BAD_RESPONSE, e.message)
        }
    }

    private suspend fun ensureModel() {
        when (client.checkStatus()) {
            FeatureStatus.UNAVAILABLE -> {
                supported = false
                throw AiException(AiException.Reason.UNSUPPORTED)
            }

            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                var failure: String? = null
                client.download().collect { status ->
                    if (status is DownloadStatus.DownloadFailed) failure = status.e.message.orEmpty()
                }
                failure?.let { throw AiException(AiException.Reason.DOWNLOAD_FAILED, it.ifBlank { null }) }
            }

            else -> Unit
        }
    }
}
