package site.chirudev.pdfutils.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import site.chirudev.pdfutils.tools.Category
import site.chirudev.pdfutils.tools.Tool
import site.chirudev.pdfutils.tools.tools
import site.chirudev.pdfutils.ui.components.EmptyState
import site.chirudev.pdfutils.ui.components.SurfaceCard
import site.chirudev.pdfutils.ui.components.ToolIcon

/** Pure filter so it can be unit-tested: free-text search plus an optional category. */
fun filterTools(all: List<Tool>, query: String, category: Category?): List<Tool> {
    val q = query.trim().lowercase()
    return all.filter { t ->
        (category == null || t.category == category) &&
            (q.isEmpty() || t.title.lowercase().contains(q) || t.description.lowercase().contains(q) || t.api.replace('_', ' ').contains(q))
    }
}

@Composable
fun HomeScreen(onOpen: (Tool) -> Unit, contentPadding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    val visible by remember { derivedStateOf { filterTools(tools, query, category) } }
    val focus = LocalFocusManager.current

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text(
                    buildAnnotatedString {
                        append("PDF")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Utils") }
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                Text("Every tool you need to work with PDFs", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Merge, split, compress, convert, rotate, protect and more — fast and free.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search tools") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "cats") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") }, shape = MaterialTheme.shapes.small) }
                items(Category.entries) { c ->
                    FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c.label) }, shape = MaterialTheme.shapes.small)
                }
            }
        }
        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                EmptyState(Icons.Rounded.SearchOff, "No tools match “$query”", "Try another word, like merge or compress.")
            }
        }
        items(visible, key = { it.slug }) { tool -> ToolCard(tool, onClick = { onOpen(tool) }, modifier = Modifier.animateItem()) }
    }
}

@Composable
fun ToolCard(tool: Tool, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SurfaceCard(modifier.fillMaxWidth().height(168.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            ToolIcon(tool, size = 44.dp)
            Spacer(Modifier.height(12.dp))
            Text(tool.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        }
    }
}
