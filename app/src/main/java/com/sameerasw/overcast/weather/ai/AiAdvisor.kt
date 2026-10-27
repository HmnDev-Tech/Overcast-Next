package com.sameerasw.overcast.weather.ai

import android.content.Context
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.provider.ProviderHttp
import com.sameerasw.overcast.weather.provider.WeatherProviderException
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class AiException(val reason: Reason, message: String? = null) : Exception(message) {
    enum class Reason { NOT_CONFIGURED, INVALID_KEY, MODEL_NOT_FOUND, RATE_LIMITED, UNAVAILABLE, NETWORK, BAD_RESPONSE }
}

object AiAdvisor {
    private const val CACHE_PREFS = "ai_advice_cache"
    private const val KEY_SIGNATURE = "signature"
    private const val KEY_TEXT = "text"

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _lastError = MutableStateFlow<AiException?>(null)
    val lastError: StateFlow<AiException?> = _lastError.asStateFlow()

    fun buildPrompt(snapshot: WeatherSnapshot, units: WeatherUnits, locale: Locale = Locale.getDefault()): String {
        val lang = locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
        val alerts = snapshot.activeAlerts().take(3).joinToString("; ") { it.headline }
        fun temp(celsius: Double) = WeatherFormat.temperature(celsius, units.temperature) + units.temperature.symbol.removePrefix("°")
        return buildString {
            append("Current weather in ${snapshot.locationName}: ")
            append("${temp(snapshot.tempC)}, feels like ${temp(snapshot.feelsLikeC)}, ")
            append("${snapshot.conditionText}, humidity ${snapshot.humidity ?: "?"}%, ")
            append("wind ${WeatherFormat.wind(snapshot.windKph, units.wind)}, ")
            append("rain chance ${snapshot.chanceOfRain ?: 0}%, ")
            append("today high ${temp(snapshot.todayHighC())} low ${temp(snapshot.todayLowC())}. ")
            if (alerts.isNotBlank()) append("Alerts: $alerts. ")
            append("Reply in $lang, max 90 words. Plain text only, no markdown, no asterisks or bullet symbols. ")
            append("Write 4 short lines, each starting with one emoji: ")
            append("what to wear, take an umbrella or not, best time to go out, one health tip.")
        }
    }

    fun cached(context: Context, snapshot: WeatherSnapshot, units: WeatherUnits): String? {
        val prefs = cachePrefs(context)
        val signature = signature(SettingsRepository(context), snapshot, units) ?: return null
        return prefs.getString(KEY_TEXT, null)?.takeIf { prefs.getString(KEY_SIGNATURE, null) == signature }
    }

    suspend fun advise(context: Context, snapshot: WeatherSnapshot, units: WeatherUnits, force: Boolean = false): String {
        if (!force) cached(context, snapshot, units)?.let { return it }
        val settings = SettingsRepository(context)
        val provider = AiProviders.byId(settings.getAiProvider())
        val apiKey = settings.getAiApiKey(provider.id)
        if (provider.isOff || apiKey == null) throw AiException(AiException.Reason.NOT_CONFIGURED)
        val model = settings.getAiModel(provider.id) ?: provider.defaultModel
        val prompt = buildPrompt(snapshot, units)
        val text = try {
            if (provider.id == AiProviders.gemini.id) {
                adviseGemini(apiKey, model, prompt)
            } else {
                adviseOpenAiCompatible(settings.getAiBaseUrl(provider.id) ?: provider.defaultBaseUrl.orEmpty(), apiKey, model, prompt)
            }
        } catch (e: AiException) {
            _lastError.value = e
            throw e
        }
        _lastError.value = null
        signature(settings, snapshot, units)?.let {
            cachePrefs(context).edit().putString(KEY_SIGNATURE, it).putString(KEY_TEXT, text).apply()
        }
        _version.update { it + 1 }
        return text
    }

    private fun signature(settings: SettingsRepository, snapshot: WeatherSnapshot, units: WeatherUnits): String? {
        val provider = AiProviders.byId(settings.getAiProvider()).takeIf { !it.isOff } ?: return null
        val model = settings.getAiModel(provider.id) ?: provider.defaultModel
        return listOf(
            provider.id,
            model,
            snapshot.updatedAt,
            snapshot.locationName,
            Locale.getDefault().toLanguageTag(),
            units.temperature.id,
            units.wind.id,
        ).joinToString("|")
    }

    private fun cachePrefs(context: Context) =
        context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    private suspend fun adviseGemini(apiKey: String, model: String, prompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${ProviderHttp.encode(model)}:generateContent"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .toString()
        val json = parse(post(url, body, mapOf("x-goog-api-key" to apiKey)))
        return json.optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.trim()
            ?.takeIf { it.isNotBlank() } ?: throw AiException(AiException.Reason.BAD_RESPONSE)
    }

    private suspend fun adviseOpenAiCompatible(baseUrl: String, apiKey: String, model: String, prompt: String): String {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 300)
            .put("temperature", 0.6)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You are a concise weather assistant."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
            .toString()
        val json = parse(post(url, body, mapOf("Authorization" to "Bearer $apiKey")))
        return json.optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
            ?.takeIf { it.isNotBlank() } ?: throw AiException(AiException.Reason.BAD_RESPONSE)
    }

    private fun parse(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (e: JSONException) {
        throw AiException(AiException.Reason.BAD_RESPONSE, e.message)
    }

    private suspend fun post(url: String, body: String, headers: Map<String, String>): String {
        val response = try {
            ProviderHttp.postForResponse(url, body, headers)
        } catch (e: WeatherProviderException) {
            throw AiException(AiException.Reason.NETWORK, e.message)
        } catch (e: ClassCastException) {
            throw AiException(AiException.Reason.BAD_RESPONSE, e.message)
        }
        if (response.code in 200..299) return response.body
        val error = runCatching { JSONObject(response.body).optJSONObject("error") }.getOrNull()
        val message = error?.optString("message")?.ifBlank { null } ?: "HTTP ${response.code}"
        val keyRejected = response.body.contains("API_KEY_INVALID") || response.body.contains("invalid_api_key")
        val reason = when {
            response.code == 401 || response.code == 403 || keyRejected -> AiException.Reason.INVALID_KEY
            response.code == 404 -> AiException.Reason.MODEL_NOT_FOUND
            response.code == 429 -> AiException.Reason.RATE_LIMITED
            response.code >= 500 -> AiException.Reason.UNAVAILABLE
            else -> AiException.Reason.BAD_RESPONSE
        }
        throw AiException(reason, message)
    }
}
