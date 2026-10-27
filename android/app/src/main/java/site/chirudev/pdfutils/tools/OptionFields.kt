package site.chirudev.pdfutils.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import site.chirudev.pdfutils.ui.theme.MonoDigits
import java.util.Locale
import kotlin.math.roundToInt

/** Renders one tool option, mirroring the web's Options.tsx controls. */
@Composable
fun OptionField(field: Field, values: Values, confirm: Map<String, String>, onValue: (String, Any) -> Unit, onConfirm: (String, String) -> Unit) {
    when (field) {
        is Field.Choice -> if (field.layout == Field.Layout.Cards) ChoiceCards(field, values[field.key] as? String ?: field.default) { onValue(field.key, it) }
        else ChoicePills(field, values[field.key] as? String ?: field.default) { onValue(field.key, it) }
        is Field.Text -> OutlinedTextField(
            value = values[field.key] as? String ?: "",
            onValueChange = { v -> onValue(field.key, field.maxLength?.let { v.take(it) } ?: v) },
            label = { Text(field.label) },
            placeholder = field.placeholder?.let { { Text(it) } },
            supportingText = field.hint?.let { { Text(it) } },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        is Field.Pages -> OutlinedTextField(
            value = values[field.key] as? String ?: "",
            onValueChange = { v -> onValue(field.key, v.filter { it.isDigit() || it == ',' || it == '-' || it == ' ' }) },
            label = { Text(field.label) },
            placeholder = field.placeholder?.let { { Text(it) } },
            supportingText = { Text(field.hint ?: PAGE_HINT) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        is Field.Password -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PasswordInput(field.label, values[field.key] as? String ?: "", field.hint) { onValue(field.key, it) }
            if (field.confirm) PasswordInput("Repeat password", confirm[field.key].orEmpty(), null) { onConfirm(field.key, it) }
        }
        is Field.Range -> RangeField(field, (values[field.key] as? Number)?.toDouble() ?: field.default) { onValue(field.key, it) }
        is Field.Color -> ColorField(field, values[field.key] as? String ?: field.default) { onValue(field.key, it) }
        is Field.Toggle -> {
            val checked = values[field.key] as? Boolean ?: field.default
            Row(
                Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch) { onValue(field.key, it) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(field.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = checked, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleSmall.merge(MonoDigits), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ChoiceCards(field: Field.Choice, selected: String, onSelect: (String) -> Unit) {
    Column {
        FieldLabel(field.label)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            field.options.forEach { opt ->
                val isSel = opt.value == selected
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(if (isSel) 2.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().selectable(isSel, role = Role.RadioButton) { onSelect(opt.value) },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = isSel, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(opt.label, style = MaterialTheme.typography.titleSmall)
                            opt.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoicePills(field: Field.Choice, selected: String, onSelect: (String) -> Unit) {
    Column {
        FieldLabel(field.label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            field.options.forEach { opt ->
                val isSel = opt.value == selected
                FilterChip(
                    selected = isSel,
                    onClick = { onSelect(opt.value) },
                    label = { Text(opt.label) },
                    leadingIcon = if (isSel) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                    shape = MaterialTheme.shapes.small,
                )
            }
        }
    }
}

@Composable
private fun PasswordInput(label: String, value: String, hint: String?, onChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide password" else "Show password")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RangeField(field: Field.Range, value: Double, onChange: (Double) -> Unit) {
    val display = if (field.step % 1.0 == 0.0) value.roundToInt().toString() else String.format(Locale.US, "%.2f", value)
    Column {
        FieldLabel(field.label, display + field.unit.orEmpty())
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = field.min + ((raw - field.min) / field.step).roundToInt() * field.step
                onChange((snapped * 10_000).roundToInt() / 10_000.0)
            },
            // Continuous track (no tick clutter); values snap to the step in onValueChange.
            valueRange = field.min.toFloat()..field.max.toFloat(),
        )
    }
}

private val swatches = listOf("#808080", "#000000", "#E5484D", "#0090FF", "#30A46C", "#F5A524", "#8E4EC6")
private val hexRe = Regex("^#[0-9A-Fa-f]{6}$")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorField(field: Field.Color, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column {
        FieldLabel(field.label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            swatches.forEach { hex ->
                val selected = hex.equals(value, ignoreCase = true)
                Box(
                    Modifier
                        .size(36.dp)
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .padding(4.dp)
                        .background(parseHex(hex), CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = hex) { onChange(hex) },
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    val t = ("#" + v.removePrefix("#").filter { it.isLetterOrDigit() }.take(6)).uppercase()
                    text = t
                    if (hexRe.matches(t)) onChange(t)
                },
                singleLine = true,
                leadingIcon = { Box(Modifier.size(20.dp).background(parseHex(value), CircleShape)) },
                shape = MaterialTheme.shapes.small,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.size(width = 140.dp, height = 56.dp),
            )
        }
    }
}

private fun parseHex(hex: String): Color = runCatching { Color(hex.toColorInt()) }.getOrDefault(Color.Gray)
