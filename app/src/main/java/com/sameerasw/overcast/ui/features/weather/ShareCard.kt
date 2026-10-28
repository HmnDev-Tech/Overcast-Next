package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.sameerasw.overcast.R
import com.sameerasw.overcast.ui.core.sheets.OvercastBottomSheet
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.weather.TemperatureUnit
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.effects.WeatherEffectSpec
import com.sameerasw.overcast.weather.effects.drawWeatherEffectsStill
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.scene.SceneType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CARD_WIDTH_PX = 1080
private val CARD_WIDTH = 360.dp
private val CARD_HEIGHT = 640.dp
private val APP_ICON_SIZE = 28.dp
private val CardDensity = Density(density = CARD_WIDTH_PX / 360f, fontScale = 1f)

@Composable
fun ShareSheet(
    snapshot: WeatherSnapshot,
    units: WeatherUnits,
    palette: WeatherPalette,
    effectSpec: WeatherEffectSpec,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val scene = rememberLocationScene(snapshot)
    val layer = rememberGraphicsLayer()
    var sharing by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }

    OvercastBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(CARD_WIDTH / CARD_HEIGHT)
                    .clip(MaterialTheme.shapes.extraLarge),
                contentAlignment = Alignment.Center,
            ) {
                val scale = with(LocalDensity.current) { maxWidth.toPx() } / CARD_WIDTH_PX
                CompositionLocalProvider(LocalDensity provides CardDensity) {
                    Box(
                        Modifier
                            .requiredSize(CARD_WIDTH, CARD_HEIGHT)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin.Center
                            }
                            .drawWithContent {
                                layer.record { this@drawWithContent.drawContent() }
                                drawLayer(layer)
                            },
                    ) {
                        ShareCardContent(snapshot, units, palette, effectSpec, scene, now)
                    }
                }
            }
            Button(
                onClick = {
                    if (sharing) return@Button
                    HapticUtil.performVirtualKeyHaptic(view)
                    sharing = true
                    scope.launch {
                        try {
                            val bitmap = layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                            val uri = withContext(Dispatchers.IO) { saveForSharing(context, bitmap) }
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("image/png")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    null,
                                ),
                            )
                        } finally {
                            sharing = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(painterResource(R.drawable.rounded_share_24), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.share_action), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

private fun saveForSharing(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "overcast-weather.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.share", file)
}

@Composable
private fun ShareCardContent(
    snapshot: WeatherSnapshot,
    units: WeatherUnits,
    palette: WeatherPalette,
    effectSpec: WeatherEffectSpec,
    scene: SceneType?,
    now: Long,
) {
    val temperature = if (units.temperature == TemperatureUnit.FAHRENHEIT) snapshot.tempC * 9 / 5 + 32 else snapshot.tempC
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to palette.glow,
                    0.45f to palette.glowSecondary,
                    0.85f to palette.base,
                    1f to palette.base,
                ),
            ),
    ) {
        SkyBody(snapshot, { now }, { 0f })
        if (scene != null) {
            Image(
                painter = painterResource(scene.drawableRes),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                colorFilter = ColorFilter.tint(sceneTint(palette)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 150.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = 0.85f
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    },
            )
        }
        Canvas(Modifier.fillMaxSize()) { drawWeatherEffectsStill(effectSpec, strength = 1.7f, areaScale = 1f) }
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            Text(
                snapshot.locationName,
                color = palette.onBase,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            Text(
                SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)),
                color = palette.onBaseMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${temperature.roundToInt()}°",
                color = palette.onBase,
                style = TextStyle(fontFamily = temperatureFont(100, 250), fontSize = 150.sp, lineHeight = 150.sp),
            )
            Text(snapshot.conditionText, color = palette.onBase, style = MaterialTheme.typography.headlineSmall)
            Text(
                "H ${WeatherFormat.temperature(snapshot.todayHighC(now), units.temperature)}  ·  L ${WeatherFormat.temperature(snapshot.todayLowC(now), units.temperature)}",
                color = palette.onBaseMuted,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.weight(0.6f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(APP_ICON_SIZE).clip(CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(APP_ICON_SIZE * 1.5f))
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(APP_ICON_SIZE * 1.5f))
                }
                Text(stringResource(R.string.app_name), color = palette.onBase, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
