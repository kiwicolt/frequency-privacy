package app.relish.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.relish.data.local.FilterType
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.Relish

/** Keyword / subreddit / user / domain block list, persisted in Room. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterScreen(vm: SettingsViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val filters by vm.filters.collectAsStateWithLifecycle()
    var type by remember { mutableStateOf(FilterType.KEYWORD) }
    var value by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Filters") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 12.dp)) {
                FilterType.entries.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    Spacer(Modifier.width(6.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value, { value = it },
                    label = { Text(type.label) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.addFilter(type, value); value = "" }, enabled = value.isNotBlank()) {
                    Icon(Icons.Default.Add, "Add filter")
                }
            }
            Text(
                "Keywords match post titles. Subreddit filters apply to mixed feeds (front page, r/all, multis) but not when you open that subreddit directly.",
                style = MetaTextStyle, color = Relish.colors.subtle, modifier = Modifier.padding(12.dp),
            )
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(filters, key = { it.id }) { f ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.type.label, style = MetaTextStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(80.dp))
                        Text(f.value, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.deleteFilter(f.id) }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
                if (filters.isEmpty()) item { Text("No filters", color = Relish.colors.subtle, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}
