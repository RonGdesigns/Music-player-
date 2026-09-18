package com.irondigital.spindle.ui.audio

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irondigital.spindle.data.settings.Settings
import com.irondigital.spindle.playback.EqualizerCapabilities
import com.irondigital.spindle.spindle
import com.irondigital.spindle.ui.components.TickScale
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel
import com.irondigital.spindle.ui.tools.ToolHeader
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class EqualizerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.spindle
    private val store = app.settingsStore

    val settings: StateFlow<Settings> =
        store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Whatever the device's own equalizer reported. Null until it has been asked. */
    val capabilities: StateFlow<EqualizerCapabilities?> = app.equalizerCapabilities.asStateFlow()

    /**
     * The curve being dragged right now.
     *
     * A fader produces a value on every frame of a drag, and writing each one
     * through to disk would be sixty DataStore commits a second. So the drag
     * drives this, the screen draws from this, and the store gets the settled
     * value a moment later — which is still fast enough that the sound moves
     * under your finger.
     */
    private val _draft = MutableStateFlow<List<Int>?>(null)
    val draft: StateFlow<List<Int>?> = _draft.asStateFlow()

    private val pending = MutableSharedFlow<List<Int>>(extraBufferCapacity = 64)

    @OptIn(FlowPreview::class)
    private val writer = viewModelScope.launch {
        pending.debounce(WRITE_DELAY_MS).collect { store.setEqualizerBands(it) }
    }

    fun setEnabled(enabled: Boolean) = edit { store.setEqualizerEnabled(enabled) }

    fun setPreset(index: Int) {
        // The device owns the curve behind a preset and never reports it back,
        // so any draft of our own would be a lie about what is being heard.
        _draft.value = null
        edit { store.setEqualizerPreset(index) }
    }

    fun setBand(index: Int, levelMb: Int) {
        val caps = capabilities.value ?: return
        val base = _draft.value ?: bandsFor(caps, settings.value)
        if (index !in base.indices) return

        val updated = base.toMutableList().also { it[index] = levelMb }
        _draft.value = updated
        pending.tryEmit(updated)
        if (settings.value.equalizerPreset != CUSTOM) edit { store.setEqualizerPreset(CUSTOM) }
    }

    fun flatten() {
        val caps = capabilities.value ?: return
        val flat = List(caps.bandCount) { 0 }
        _draft.value = flat
        edit {
            store.setEqualizerBands(flat)
            store.setEqualizerPreset(CUSTOM)
        }
    }

    fun useCustomCurve() {
        val caps = capabilities.value ?: return
        _draft.value = bandsFor(caps, settings.value)
        edit { store.setEqualizerPreset(CUSTOM) }
    }

    /**
     * Forces the settled curve out now. The debounce above means the last move
     * of a fader can still be in flight when the screen closes, and an
     * equalizer that forgets the adjustment you just made is worse than one
     * that saves a frame late.
     */
    fun commitDraft() {
        _draft.value?.let { curve -> edit { store.setEqualizerBands(curve) } }
    }

    fun setBassBoost(strength: Int) = edit { store.setBassBoost(strength) }
    fun setVirtualizer(strength: Int) = edit { store.setVirtualizer(strength) }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    override fun onCleared() {
        writer.cancel()
        super.onCleared()
    }

    companion object {
        const val CUSTOM = -1
        private const val WRITE_DELAY_MS = 120L

        /** A saved curve padded or trimmed to this device's actual band count. */
        fun bandsFor(caps: EqualizerCapabilities, settings: Settings): List<Int> =
            List(caps.bandCount) { settings.equalizerBands.getOrNull(it) ?: 0 }
    }
}

/**
 * The equalizer, built from whatever this device turned out to have.
 *
 * Nothing about an Android equalizer is fixed — five bands on one phone, ten on
 * another, a different gain range and a different set of presets on each — so
 * every control here is drawn from what the hardware reported rather than from
 * a layout that assumes.
 */
@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val viewModel: EqualizerViewModel = viewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    DisposableEffect(Unit) { onDispose { viewModel.commitDraft() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
    ) {
        ToolHeader(
            title = "Equalizer",
            explanation = "Shapes the sound on its way out, using the effects this " +
                "device provides. Nothing is written to your files.",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Equalizer",
                    style = SpindleType.Section,
                    color = Ink.Primary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.equalizerEnabled,
                    onCheckedChange = viewModel::setEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ink.OnLamp,
                        checkedTrackColor = Lamp.Bright,
                        uncheckedThumbColor = Steel.Dim,
                        uncheckedTrackColor = Ground.Raised,
                        uncheckedBorderColor = Steel.Engrave,
                    ),
                )
            }

            val caps = capabilities
            when {
                caps == null -> Note(
                    "Reading what this device offers. If this stays here, play " +
                        "something for a moment — the equalizer attaches to the " +
                        "player's own audio output, so it needs the player awake."
                )

                !caps.isUsable -> Note(
                    "This device does not provide an equalizer that Spindle can " +
                        "attach to. Some manufacturers reserve it for their own " +
                        "sound app, and there is no way around that from here."
                )

                else -> EqualizerBody(
                    caps = caps,
                    settings = settings,
                    draft = draft,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun EqualizerBody(
    caps: EqualizerCapabilities,
    settings: Settings,
    draft: List<Int>?,
    viewModel: EqualizerViewModel,
) {
    val dimmed = !settings.equalizerEnabled
    val usingPreset = settings.equalizerPreset >= 0 &&
        settings.equalizerPreset < caps.presetNames.size

    if (caps.presetNames.isNotEmpty()) {
        Spacer(Modifier.height(Space.m))
        Text(
            text = "Presets",
            style = SpindleType.RowTitle,
            color = if (dimmed) Steel.Dim else Ink.Primary,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )
        Spacer(Modifier.height(Space.s))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Chip(
                label = "Custom",
                selected = !usingPreset,
                enabled = !dimmed,
                onClick = viewModel::useCustomCurve,
            )
            caps.presetNames.forEachIndexed { index, name ->
                Chip(
                    label = name.ifBlank { "Preset ${index + 1}" },
                    selected = usingPreset && settings.equalizerPreset == index,
                    enabled = !dimmed,
                    onClick = { viewModel.setPreset(index) },
                )
            }
        }
    }

    Spacer(Modifier.height(Space.l))
    TickScale(
        height = 10.dp,
        spacing = 6.dp,
        modifier = Modifier.padding(horizontal = Space.gutter),
    )
    Spacer(Modifier.height(Space.m))

    if (usingPreset) {
        Note(
            "Using this device's \"${caps.presetNames[settings.equalizerPreset]}\" " +
                "preset. The device keeps that curve to itself, so rather than draw " +
                "bands that might be wrong, Spindle shows none — tap Custom to set " +
                "them yourself."
        )
    } else {
        val bands = draft ?: EqualizerViewModel.bandsFor(caps, settings)
        val span = (caps.maxLevelMb - caps.minLevelMb).coerceAtLeast(1)
        val detent = ((0 - caps.minLevelMb).toFloat() / span).coerceIn(0f, 1f)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            caps.centerFrequenciesHz.forEachIndexed { index, hz ->
                val levelMb = bands.getOrElse(index) { 0 }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatGain(levelMb),
                        style = SpindleType.DataEmphasis,
                        color = if (dimmed) Steel.Dim else Lamp.Bright,
                    )
                    Spacer(Modifier.height(Space.xs))
                    VerticalFader(
                        value = ((levelMb - caps.minLevelMb).toFloat() / span).coerceIn(0f, 1f),
                        onValueChange = { fraction ->
                            viewModel.setBand(index, caps.minLevelMb + (fraction * span).toInt())
                        },
                        label = "${formatFrequency(hz)} band",
                        readout = formatGain(levelMb),
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = formatFrequency(hz),
                        style = SpindleType.Data,
                        color = Steel.Bright,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.m))
        Text(
            text = "Flatten",
            style = SpindleType.RowTitle,
            color = if (dimmed) Steel.Dim else Lamp.Bright,
            modifier = Modifier
                .padding(horizontal = Space.gutter)
                .clickable(enabled = !dimmed, onClick = viewModel::flatten)
                .padding(vertical = Space.s),
        )
    }

    Spacer(Modifier.height(Space.l))
    TickScale(
        height = 10.dp,
        spacing = 6.dp,
        modifier = Modifier.padding(horizontal = Space.gutter),
    )
    Spacer(Modifier.height(Space.m))

    Column(modifier = Modifier.padding(horizontal = Space.gutter)) {
        StrengthRow(
            title = "Bass boost",
            description = "Lifts the low end below where the bands reach. Heavy " +
                "handed on most devices — a little goes a long way.",
            strength = settings.bassBoostStrength,
            enabled = !dimmed,
            onChange = viewModel::setBassBoost,
        )
        Spacer(Modifier.height(Space.m))
        StrengthRow(
            title = "Widen",
            description = "Pushes the stereo image outward. Worth having on " +
                "headphones, usually worth leaving off on a speaker.",
            strength = settings.virtualizerStrength,
            enabled = !dimmed,
            onChange = viewModel::setVirtualizer,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            text = "Both of these come from the device, and a device is free to " +
                "refuse either one. Where that happens the control simply has no " +
                "effect — nothing is broken.",
            style = SpindleType.Data,
            color = Steel.Dim,
        )
        Spacer(Modifier.height(Space.xxl))
    }
}

@Composable
private fun StrengthRow(
    title: String,
    description: String,
    strength: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = SpindleType.RowTitle,
                color = if (enabled) Ink.Primary else Steel.Dim,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (strength <= 0) "Off" else "${strength / 10}%",
                style = SpindleType.DataEmphasis,
                color = if (enabled && strength > 0) Lamp.Bright else Steel.Dim,
            )
        }
        Slider(
            value = strength.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..1000f,
            steps = 19,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Lamp.Bright,
                activeTrackColor = Lamp.Bright,
                inactiveTrackColor = Steel.Engrave,
                activeTickColor = Ground.Deep,
                inactiveTickColor = Steel.Engrave,
                disabledThumbColor = Steel.Dim,
                disabledActiveTrackColor = Steel.Engrave,
                disabledInactiveTrackColor = Steel.Engrave,
            ),
        )
        Text(description, style = SpindleType.Secondary, color = Steel.Dim)
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(if (selected) Lamp.Bright else Ground.Raised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.s),
    ) {
        Text(
            text = label,
            style = SpindleType.Secondary,
            color = when {
                selected -> Ink.OnLamp
                enabled -> Steel.Bright
                else -> Steel.Dim
            },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = SpindleType.Body,
        color = Steel.Bright,
        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
    )
}

/** Millibels are the platform's unit and nobody's reading unit. */
private fun formatGain(levelMb: Int): String {
    val db = levelMb / 100f
    return when {
        db > 0.05f -> String.format(Locale.US, "+%.1f", db)
        db < -0.05f -> String.format(Locale.US, "%.1f", db)
        else -> "0.0"
    }
}

private fun formatFrequency(hz: Int): String {
    if (hz < 1000) return "$hz"
    val k = hz / 1000f
    return if (k == k.toInt().toFloat()) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
}
