package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sameerasw.overcast.weather.provider.ProviderHttp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.radar.RadarMap
import com.sameerasw.overcast.weather.radar.RadarProviders
import com.sameerasw.overcast.weather.radar.RadarRepository
import kotlinx.coroutines.CancellationException

@Composable
fun RadarSection(snapshot: WeatherSnapshot, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    var providerId by remember { mutableStateOf(settings.getRadarProvider()) }
    var configVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsRepository.KEY_RADAR_PROVIDER || key?.startsWith(SettingsRepository.KEY_WEATHER_API_KEY) == true) {
                providerId = settings.getRadarProvider()
                configVersion++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled = RadarProviders.resolve(providerId) != RadarProviders.OFF_ID
    var map by remember { mutableStateOf<RadarMap?>(null) }

    LaunchedEffect(enabled, configVersion, snapshot.updatedAt, snapshot.locationName) {
        if (!enabled) {
            map = null
            return@LaunchedEffect
        }
        map = try {
            RadarRepository.current(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    AnimatedVisibility(
        visible = enabled && map != null,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val shown = remember { mutableStateOf(map) }
        map?.let { shown.value = it }
        val radar = shown.value ?: return@AnimatedVisibility
        Surface(
            color = palette.card,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        painterResource(R.drawable.rounded_radar_24),
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        stringResource(R.string.radar_title),
                        color = palette.onBase,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    radar.timeMillis?.let {
                        Text(
                            DateFormat.getTimeFormat(context).format(it),
                            color = palette.onBaseMuted,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                RadarTiles(radar, palette)
                Text(
                    radar.attribution,
                    color = palette.onBaseMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// Inverts the light OSM tiles and dims them so the map sits under the dark card.
private val DarkMapFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.6f, 0f, 0f, 0f, 160f,
            0f, -0.6f, 0f, 0f, 160f,
            0f, 0f, -0.55f, 0f, 165f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@Composable
private fun RadarTiles(radar: RadarMap, palette: WeatherPalette) {
    val context = LocalContext.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .clip(MaterialTheme.shapes.large)
            .background(Color(0xFF0E0E10)),
    ) {
        val tile = maxWidth / 2
        val originX = maxWidth / 2 - tile * (1f + radar.offsetX)
        val originY = maxHeight / 2 - tile * (1f + radar.offsetY)
        radar.tiles.forEach { t ->
            val tileModifier = Modifier
                .offset(originX + tile * (t.dx + 1), originY + tile * (t.dy + 1))
                .size(tile)
            AsyncImage(
                model = ImageRequest.Builder(context).data(t.baseUrl).addHeader("User-Agent", ProviderHttp.USER_AGENT).build(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                colorFilter = DarkMapFilter,
                modifier = tileModifier,
            )
            AsyncImage(model = t.overlayUrl, contentDescription = null, contentScale = ContentScale.FillBounds, alpha = 0.85f, modifier = tileModifier)
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(12.dp)
                .clip(CircleShape)
                .background(palette.accent)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}
