package com.sameerasw.overcast.weather.ai

import android.content.Context
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.provider.ProviderHttp
import com.sameerasw.overcast.weather.provider.WeatherProviderException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class AiException(val reason: Reason, message: String? = null) : Exception(message) {
    enum class Reason { NOT_CONFIGURED, INVALID_KEY, RATE_LIMITED, NETWORK, BAD_RESPONSE }
}

object AiAdvisor {
    private const val CACHE_PREFS = "ai_advice_cache"
    private const val KEY_SIGNATURE = "signature"
    private const val KEY_TEXT = "text"

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
            append("Reply in $lang, max 90 words, as short bullet lines with emoji: ")
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
        val text = if (provider.id == AiProviders.gemini.id) {
            adviseGemini(apiKey, model, prompt)
        } else {
            adviseOpenAiCompatible(settings.getAiBaseUrl(provider.id) ?: provider.defaultBaseUrl.orEmpty(), apiKey, model, prompt)
        }
        signature(settings, snapshot, units)?.let {
            cachePrefs(context).edit().putString(KEY_SIGNATURE, it).putString(KEY_TEXT, text).apply()
        }
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

    private fun parse(raw: String): JSONObject {
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw AiException(AiException.Reason.BAD_RESPONSE, e.message)
        }
        json.optJSONObject("error")?.let { throw AiException(AiException.Reason.BAD_RESPONSE, it.optString("message").ifBlank { null }) }
        return json
    }

    private suspend fun post(url: String, body: String, headers: Map<String, String>): String {
        try {
            return ProviderHttp.post(url, body, headers)
        } catch (e: WeatherProviderException) {
            throw AiException(
                when (e.reason) {
                    WeatherProviderException.Reason.INVALID_KEY -> AiException.Reason.INVALID_KEY
                    WeatherProviderException.Reason.RATE_LIMITED -> AiException.Reason.RATE_LIMITED
                    WeatherProviderException.Reason.NETWORK -> AiException.Reason.NETWORK
                    WeatherProviderException.Reason.BAD_RESPONSE -> AiException.Reason.BAD_RESPONSE
                },
                e.message,
            )
        }
    }
}
