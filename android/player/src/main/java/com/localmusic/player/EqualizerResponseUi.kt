package com.localmusic.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localmusic.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*

@Composable
internal fun EqResponseCard(preset: EqPreset, eqEnabled: Boolean, ready: Boolean, onPreamp: (Double) -> Unit) {
    val response = remember(preset) { EqResponse(preset) }
    val points = remember(response) { response.curve() }
    val headroomResult by produceState<Pair<EqResponse, EqHeadroom>?>(initialValue = null, response) {
        value = withContext(Dispatchers.Default) { response to response.headroom() }
    }
    val headroom = headroomResult?.takeIf { it.first === response }?.second
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val bounds = remember(points) {
        val top = maxOf(12.0, ceil(points.maxOf { it.gainDb } / 6) * 6)
        val bottom = minOf(-12.0, floor(points.minOf { it.gainDb } / 6) * 6)
        bottom to top
    }
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Combined EQ response", style = MaterialTheme.typography.titleMedium)
            Text(if (eqEnabled) "Preset preview including preamp" else "EQ is off · saved preset preview",
                style = MaterialTheme.typography.bodySmall)
            Text("Top %+.0f dB · bottom %+.0f dB".format(bounds.second, bounds.first),
                style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(132.dp).semantics {
                contentDescription = "Combined equalizer response from 20 hertz to 20 kilohertz, including preamp; preview at 48 kilohertz"
            }) {
                fun y(db: Double) = ((bounds.second - db) / (bounds.second - bounds.first) * size.height).toFloat()
                fun x(hz: Double) = (ln(hz / 20) / ln(1000.0) * size.width).toFloat()
                for (hz in listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)) {
                    drawLine(grid, Offset(x(hz), 0f), Offset(x(hz), size.height), 1.dp.toPx())
                }
                for (db in listOf(bounds.first, 0.0, bounds.second)) {
                    drawLine(grid, Offset(0f, y(db)), Offset(size.width, y(db)), 1.dp.toPx())
                }
                val path = Path()
                points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(x(point.frequencyHz), y(point.gainDb))
                    else path.lineTo(x(point.frequencyHz), y(point.gainDb))
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("20 Hz", "20k Hz").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("Grid: 100 Hz · 1 kHz · 10 kHz", style = MaterialTheme.typography.labelSmall)
            Text("48 kHz preview · excludes ReplayGain", style = MaterialTheme.typography.labelSmall)
            headroom?.let { estimate ->
                val remainingBoost = estimate.peakFilterGainDb + preset.preampDb
                Text(if (remainingBoost > .05) "Estimated peak boost: +%.1f dB".format(remainingBoost)
                    else "No estimated EQ boost above 0 dB", style = MaterialTheme.typography.bodyMedium)
                if (preset.preampDb > estimate.recommendedPreampDb + .05) {
                    OutlinedButton(enabled = ready, onClick = { onPreamp(estimate.recommendedPreampDb) }) {
                        Text("Lower preamp to %.1f dB".format(estimate.recommendedPreampDb))
                    }
                }
                if (estimate.peakFilterGainDb > 24.0) {
                    Text("Boost exceeds the preamp range. Reduce filter gains.", color = MaterialTheme.colorScheme.error)
                }
            } ?: Text("Estimating headroom…", style = MaterialTheme.typography.bodySmall)
            Text("Headroom is an estimate, not a limiter. Lower the preamp further if you hear distortion.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun EqExactValuesDialog(filter: EqFilter, ready: Boolean, onDismiss: () -> Unit, onApply: (EqFilter) -> Unit) {
    fun initial(value: Double) = String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
    var frequency by rememberSaveable(filter) { mutableStateOf(initial(filter.frequencyHz)) }
    var gain by rememberSaveable(filter) { mutableStateOf(initial(filter.gainDb)) }
    var q by rememberSaveable(filter) { mutableStateOf(initial(filter.q)) }
    fun number(text: String) = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    val frequencyValue = number(frequency)?.takeIf { it in 10.0..40000.0 }
    val gainValue = number(gain)?.takeIf { it in -24.0..24.0 }
    val qValue = number(q)?.takeIf { it in .1..20.0 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Exact filter values") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(filter.type.name.lowercase().replace('_', ' '))
            OutlinedTextField(frequency, { frequency = it.take(24) }, label = { Text("Frequency (Hz)") },
                supportingText = { Text("10–40,000 Hz") }, isError = frequencyValue == null, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(gain, { gain = it.take(24) }, label = { Text("Gain (dB)") },
                supportingText = { Text("−24 to +24 dB") }, isError = gainValue == null, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            OutlinedTextField(q, { q = it.take(24) }, label = { Text("Q") },
                supportingText = { Text("0.1–20 · higher values make a narrower filter") }, isError = qValue == null,
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }, confirmButton = {
        TextButton(enabled = ready && frequencyValue != null && gainValue != null && qValue != null, onClick = {
            if (frequencyValue != null && gainValue != null && qValue != null) {
                onApply(filter.copy(frequencyHz = frequencyValue, gainDb = gainValue, q = qValue))
            }
        }) { Text("Apply") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
