package com.sameerasw.overcast.ui.features.weather

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.layer.GraphicsLayer
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
import com.sameerasw.overcast.ui.core.containers.RoundedCardContainer
import com.sameerasw.overcast.ui.core.sheets.OvercastBottomSheet
import com.sameerasw.overcast.ui.theme.OvercastTheme
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.weather.TemperatureUnit
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.effects.WeatherEffectSpec
import com.sameerasw.overcast.weather.effects.drawWeatherEffectsStill
import com.sameerasw.overcast.weather.model.HourlyForecast
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.scene.SceneType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CARD_WIDTH_PX = 1080
private val CARD_WIDTH = 360.dp
private val CARD_HEIGHT = 640.dp
private val APP_ICON_SIZE = 28.dp
private val CardDensity = Density(density = CARD_WIDTH_PX / 360f, fontScale = 1f)
private const val FORECAST_HOURS = 24
private const val PREVIEW_WIDTH = 0.62f
private const val PREVIEW_MIN_WIDTH = 0.36f
private val PREVIEW_COLLAPSE = 220.dp

private enum class ShareExtra(val labelRes: Int, val iconRes: Int, val default: Boolean) {
    EFFECTS(R.string.share_include_effects, R.drawable.rounded_rainy_24, true),
    LANDSCAPE(R.string.share_include_landscape, R.drawable.rounded_landscape_24, true),
    DETAILS(R.string.share_include_details, R.drawable.rounded_dashboard_24, false),
    HOURLY(R.string.share_include_hourly, R.drawable.rounded_schedule_24, false),
    SUN(R.string.share_include_sun, R.drawable.rounded_wb_sunny_24, false),
}

private class ShareMoment(val snapshot: WeatherSnapshot, val palette: WeatherPalette, val effectSpec: WeatherEffectSpec, val timeMillis: Long, val hours: List<HourlyForecast>)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
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
    var busy by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    val hours = remember(snapshot) { snapshot.upcomingHours(now).take(FORECAST_HOURS) }
    var hourIndex by rememberSaveable { mutableIntStateOf(0) }
    var extras by rememberSaveable { mutableStateOf(ShareExtra.entries.filter { it.default }.map { it.name }.toSet()) }
    val moment = remember(snapshot, hourIndex, palette, effectSpec) {
        val hour = hours.getOrNull(hourIndex)?.takeIf { hourIndex > 0 }
        if (hour == null) {
            ShareMoment(snapshot, palette, effectSpec, now, hours)
        } else {
            val atHour = snapshot.copy(
                tempC = hour.tempC,
                feelsLikeC = hour.feelsLikeC ?: hour.tempC,
                condition = hour.condition,
                conditionText = conditionLabel(context, hour.condition),
                isDay = hour.isDay,
                humidity = hour.humidity ?: snapshot.humidity,
                windKph = hour.windKph ?: snapshot.windKph,
                chanceOfRain = hour.chanceOfRain,
            )
            ShareMoment(
                atHour,
                WeatherPalette.from(atHour, hour.timeMillis),
                if (effectSpec.isEmpty) effectSpec else WeatherEffectSpec.from(atHour),
                hour.timeMillis,
                hours.drop(hourIndex),
            )
        }
    }

    fun capture(then: suspend (Bitmap) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                then(layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false))
            } finally {
                busy = false
            }
        }
    }

    fun save() = capture { bitmap ->
        val saved = withContext(Dispatchers.IO) { runCatching { saveToGallery(context, bitmap) }.getOrDefault(false) }
        if (saved) HapticUtil.performConfirmHaptic(view) else HapticUtil.performRejectHaptic(view)
        Toast.makeText(context, if (saved) R.string.share_saved else R.string.share_save_failed, Toast.LENGTH_SHORT).show()
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else HapticUtil.performRejectHaptic(view)
    }

    val collapseRangePx = with(LocalDensity.current) { PREVIEW_COLLAPSE.toPx() }
    var collapsedPx by remember { mutableFloatStateOf(0f) }
    val controlsScroll = rememberScrollState()
    val previewCollapse = remember(collapseRangePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || controlsScroll.maxValue == 0) return Offset.Zero
                val next = (collapsedPx - available.y).coerceAtMost(collapseRangePx)
                val used = next - collapsedPx
                collapsedPx = next
                return Offset(0f, -used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                val next = (collapsedPx - available.y).coerceAtLeast(0f)
                val used = collapsedPx - next
                collapsedPx = next
                return Offset(0f, used)
            }
        }
    }

    OvercastTheme {
        OvercastBottomSheet(onDismissRequest = onDismiss) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .nestedScroll(previewCollapse)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CardPreview(layer, lerp(PREVIEW_WIDTH, PREVIEW_MIN_WIDTH, collapsedPx / collapseRangePx)) {
                    ShareCardContent(moment, units, scene, extras)
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(controlsScroll),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {

                    RoundedCardContainer(Modifier.fillMaxWidth(), spacing = 2.dp, cornerRadius = 24.dp) {
                        if (hours.size > 1) {
                            Surface(color = MaterialTheme.colorScheme.surfaceBright, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(painterResource(R.drawable.rounded_schedule_24), contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.size(10.dp))
                                        Text(
                                            if (hourIndex == 0) stringResource(R.string.share_time_now) else hourLabel(context, moment.timeMillis),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                    }
                                    Slider(
                                        value = hourIndex.toFloat(),
                                        onValueChange = {
                                            val next = it.roundToInt()
                                            if (next != hourIndex) {
                                                HapticUtil.performSliderHaptic(view)
                                                hourIndex = next
                                            }
                                        },
                                        valueRange = 0f..(hours.size - 1).toFloat(),
                                        steps = (hours.size - 2).coerceAtLeast(0),
                                        colors = SliderDefaults.colors(
                                            activeTickColor = Color.Transparent,
                                            inactiveTickColor = Color.Transparent,
                                        ),
                                    )
                                }
                            }
                        }

                        Surface(color = MaterialTheme.colorScheme.surfaceBright, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.fillMaxWidth()) {
                            FlowRow(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ShareExtra.entries.forEach { extra ->
                                    if (extra == ShareExtra.LANDSCAPE && scene == null) return@forEach
                                    val on = extra.name in extras
                                    Surface(
                                        onClick = {
                                            HapticUtil.performLightHaptic(view)
                                            extras = if (on) extras - extra.name else extras + extra.name
                                        },
                                        shape = CircleShape,
                                        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Icon(painterResource(extra.iconRes), contentDescription = null, modifier = Modifier.size(18.dp))
                                            Text(stringResource(extra.labelRes), style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                            }
                        }

                        val height = SplitButtonDefaults.MediumContainerHeight
                        Surface(color = MaterialTheme.colorScheme.surfaceBright, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.fillMaxWidth()) {
                            SplitButtonLayout(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                leadingButton = {
                                    SplitButtonDefaults.LeadingButton(
                                        onClick = {
                                            HapticUtil.performVirtualKeyHaptic(view)
                                            capture { bitmap ->
                                                val uri = withContext(Dispatchers.IO) { cacheForSharing(context, bitmap) }
                                                context.startActivity(
                                                    Intent.createChooser(
                                                        Intent(Intent.ACTION_SEND)
                                                            .setType("image/png")
                                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                                        null,
                                                    ),
                                                )
                                            }
                                        },
                                        shapes = SplitButtonDefaults.leadingButtonShapesFor(height),
                                        contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(height),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = height),
                                    ) {
                                        Icon(painterResource(R.drawable.rounded_share_24), contentDescription = null, modifier = Modifier.size(SplitButtonDefaults.leadingButtonIconSizeFor(height)))
                                        Spacer(Modifier.size(8.dp))
                                        Text(stringResource(R.string.share_action), style = MaterialTheme.typography.titleMedium)
                                    }
                                },
                                trailingButton = {
                                    SplitButtonDefaults.TrailingButton(
                                        onClick = {
                                            HapticUtil.performVirtualKeyHaptic(view)
                                            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                                            if (needsPermission) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else save()
                                        },
                                        shapes = SplitButtonDefaults.trailingButtonShapesFor(height),
                                        contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(height),
                                        modifier = Modifier.heightIn(min = height),
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.rounded_download_24),
                                            contentDescription = stringResource(R.string.share_save),
                                            modifier = Modifier.size(SplitButtonDefaults.trailingButtonIconSizeFor(height)),
                                        )
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CardPreview(layer: GraphicsLayer, widthFraction: Float, content: @Composable () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth(widthFraction)
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
            ) { content() }
        }
    }
}

private fun hourLabel(context: Context, millis: Long): String =
    (if (DateUtils.isToday(millis)) "" else dayName(millis) + " ") + formatHour(context, millis)

private fun cacheForSharing(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "overcast-weather.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.share", file)
}

private fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
    val name = "Overcast-${System.currentTimeMillis()}.png"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Overcast")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
    }
    @Suppress("DEPRECATION")
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Overcast").apply { mkdirs() }
    val file = File(dir, name)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
    return true
}

@Composable
private fun ShareCardContent(moment: ShareMoment, units: WeatherUnits, scene: SceneType?, extras: Set<String>) {
    val context = LocalContext.current
    val snapshot = moment.snapshot
    val palette = moment.palette
    fun temp(celsius: Double) = WeatherFormat.temperature(celsius, units.temperature)
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
        SkyBody(snapshot, { moment.timeMillis }, { 0f })
        if (scene != null && ShareExtra.LANDSCAPE.name in extras) {
            Image(
                painter = painterResource(scene.drawableRes),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                colorFilter = ColorFilter.tint(sceneTint(palette)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
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
        if (ShareExtra.EFFECTS.name in extras) {
            Canvas(Modifier.fillMaxSize()) { drawWeatherEffectsStill(moment.effectSpec, strength = 1.7f, areaScale = 1f) }
        }
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            Text(
                snapshot.locationName,
                color = palette.onBase,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            Text(
                SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(moment.timeMillis)) +
                    if (moment.timeMillis > System.currentTimeMillis() + 30 * 60_000L) " · ${formatHour(context, moment.timeMillis)}" else "",
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
                "H ${temp(snapshot.todayHighC(moment.timeMillis))}  ·  L ${temp(snapshot.todayLowC(moment.timeMillis))}",
                color = palette.onBaseMuted,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (ShareExtra.DETAILS.name in extras) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    CardStat(stringResource(R.string.weather_detail_feels_like), temp(snapshot.feelsLikeC), palette, Modifier.weight(1f))
                    CardStat(stringResource(R.string.weather_detail_wind), WeatherFormat.wind(snapshot.windKph, units.wind), palette, Modifier.weight(1f))
                    snapshot.humidity?.let { CardStat(stringResource(R.string.weather_detail_humidity), "$it%", palette, Modifier.weight(1f)) }
                    snapshot.chanceOfRain?.let { CardStat(stringResource(R.string.share_rain), "$it%", palette, Modifier.weight(1f)) }
                }
            }
            if (ShareExtra.HOURLY.name in extras && moment.hours.size > 1) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    moment.hours.drop(1).take(5).forEach { hour ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(formatHour(context, hour.timeMillis), color = palette.onBaseMuted, style = MaterialTheme.typography.labelMedium)
                            Text(temp(hour.tempC), color = palette.onBase, style = MaterialTheme.typography.titleMedium)
                            Text("${hour.chanceOfRain}%", color = palette.onBaseMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (ShareExtra.SUN.name in extras) {
                val extrasData = snapshot.extras
                if (extrasData?.sunriseMillis != null && extrasData.sunsetMillis != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                        CardStat(stringResource(R.string.weather_detail_sunrise), formatHour(context, extrasData.sunriseMillis), palette, Modifier.weight(1f))
                        CardStat(stringResource(R.string.weather_detail_sunset), formatHour(context, extrasData.sunsetMillis), palette, Modifier.weight(1f))
                    }
                }
            }
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

@Composable
private fun CardStat(label: String, value: String, palette: WeatherPalette, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = palette.onBaseMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(value, color = palette.onBase, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}
