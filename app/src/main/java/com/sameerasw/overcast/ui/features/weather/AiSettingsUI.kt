package com.sameerasw.overcast.ui.features.weather

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.ui.components.menus.SegmentedDropdownMenuItem
import com.sameerasw.overcast.ui.core.cards.ConfigPickerItem
import com.sameerasw.overcast.ui.core.cards.IconToggleItem
import com.sameerasw.overcast.ui.core.containers.RoundedCardContainer
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.weather.ai.AiProvider
import com.sameerasw.overcast.weather.ai.AiProviders

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsUI(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    var providerId by remember { mutableStateOf(AiProviders.byId(settings.getAiProvider()).id) }
    val provider = AiProviders.byId(providerId)
    var shownProviderId by remember { mutableStateOf(providerId.takeIf { !provider.isOff } ?: AiProviders.gemini.id) }
    val offLabel = stringResource(R.string.widget_forecast_off)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle(R.string.ai_section_title)
        RoundedCardContainer(spacing = 2.dp, cornerRadius = 24.dp) {
            ConfigPickerItem(
                title = stringResource(R.string.ai_provider_title),
                selectedValue = if (provider.isOff) offLabel else provider.displayName,
                iconRes = R.drawable.rounded_model_training_24,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AiProviders.all.forEach { option ->
                    SegmentedDropdownMenuItem(
                        text = { Text(if (option.isOff) offLabel else option.displayName) },
                        onClick = {
                            if (option.id != providerId) {
                                settings.setAiProvider(option.id)
                                providerId = option.id
                                if (!option.isOff) shownProviderId = option.id
                            }
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = !provider.isOff,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                AiProviderConfig(AiProviders.byId(shownProviderId), settings)
            }
        }
    }
}

@Composable
private fun AiProviderConfig(provider: AiProvider, settings: SettingsRepository) {
    val context = LocalContext.current
    val view = LocalView.current
    var savedKey by remember(provider.id) { mutableStateOf(settings.getAiApiKey(provider.id).orEmpty()) }
    var savedModel by remember(provider.id) { mutableStateOf(settings.getAiModel(provider.id).orEmpty()) }
    var savedBaseUrl by remember(provider.id) { mutableStateOf(settings.getAiBaseUrl(provider.id).orEmpty()) }
    var keyInput by remember(provider.id) { mutableStateOf(savedKey) }
    var modelInput by remember(provider.id) { mutableStateOf(savedModel) }
    var baseUrlInput by remember(provider.id) { mutableStateOf(savedBaseUrl) }
    var keyVisible by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val changed = keyInput != savedKey || modelInput != savedModel || (provider.needsBaseUrl && baseUrlInput != savedBaseUrl)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceBright,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it.trim() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.weather_api_key_title)) },
                leadingIcon = { Icon(painterResource(R.drawable.rounded_key_24), contentDescription = null) },
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            HapticUtil.performVirtualKeyHaptic(view)
                            clipboardText(context)?.let { keyInput = it.trim() }
                        }) {
                            Icon(painterResource(R.drawable.rounded_content_paste_24), contentDescription = stringResource(R.string.shorten_paste_button))
                        }
                        IconButton(onClick = {
                            HapticUtil.performVirtualKeyHaptic(view)
                            keyVisible = !keyVisible
                        }) {
                            Icon(
                                painterResource(if (keyVisible) R.drawable.rounded_visibility_off_24 else R.drawable.rounded_visibility_24),
                                contentDescription = null,
                            )
                        }
                    }
                },
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                shape = RoundedCornerShape(16.dp),
            )
        }
        IconToggleItem(
            iconRes = R.drawable.rounded_tune_24,
            title = stringResource(R.string.ai_advanced_title),
            showToggle = false,
            onClick = { advancedExpanded = !advancedExpanded },
            trailingContent = {
                Icon(
                    painter = painterResource(if (advancedExpanded) R.drawable.rounded_keyboard_arrow_up_24 else R.drawable.rounded_keyboard_arrow_down_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        AnimatedVisibility(
            visible = advancedExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceBright,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.ai_model_title)) },
                        placeholder = { Text(provider.defaultModel) },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        shape = RoundedCornerShape(16.dp),
                    )
                    if (provider.needsBaseUrl) {
                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = { baseUrlInput = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.ai_base_url_title)) },
                            placeholder = { Text(provider.defaultBaseUrl.orEmpty()) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceBright,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                provider.signupUrl?.let { url ->
                    TextButton(onClick = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        openUrl(context, url)
                    }) {
                        Text(stringResource(R.string.weather_get_api_key))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        settings.setAiApiKey(provider.id, keyInput)
                        settings.setAiModel(provider.id, modelInput)
                        if (provider.needsBaseUrl) settings.setAiBaseUrl(provider.id, baseUrlInput)
                        savedKey = keyInput
                        savedModel = modelInput
                        savedBaseUrl = baseUrlInput
                    },
                    enabled = changed,
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
