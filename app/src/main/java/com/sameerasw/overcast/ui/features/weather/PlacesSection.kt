package com.sameerasw.overcast.ui.features.weather

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.ui.components.WeatherIcon
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.extra.PlaceConditions
import com.sameerasw.overcast.weather.extra.PlacesRepository
import com.sameerasw.overcast.weather.location.WeatherPlace
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlinx.coroutines.CancellationException

@Composable
fun PlacesSection(
    snapshot: WeatherSnapshot,
    units: WeatherUnits,
    palette: WeatherPalette,
    onSelect: (WeatherPlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var places by remember { mutableStateOf<List<PlaceConditions>>(emptyList()) }
    LaunchedEffect(snapshot.updatedAt) {
        places = try {
            PlacesRepository.saved(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            places
        }
    }
    if (places.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = palette.card,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.rainSurface("places").fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        HapticUtil.performVirtualKeyHaptic(view)
                        expanded = !expanded
                    }
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(painterResource(R.drawable.rounded_location_city_24), contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.places_title), color = palette.onBase, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(places.size.toString(), color = palette.onBaseMuted, style = MaterialTheme.typography.labelLarge)
                Icon(
                    painterResource(if (expanded) R.drawable.rounded_keyboard_arrow_up_24 else R.drawable.rounded_keyboard_arrow_down_24),
                    contentDescription = null,
                    tint = palette.onBaseMuted,
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    places.forEach { item ->
                        val current = item.place.name == snapshot.locationName
                        Surface(
                            onClick = {
                                HapticUtil.performVirtualKeyHaptic(view)
                                if (!current) onSelect(item.place)
                            },
                            color = if (current) palette.accent.copy(alpha = 0.14f) else Color.Transparent,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    item.place.name,
                                    color = palette.onBase,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                WeatherIcon(item.condition, item.isDay, palette.accent, Modifier.size(26.dp))
                                Text(
                                    WeatherFormat.temperature(item.tempC, units.temperature),
                                    color = palette.onBase,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
