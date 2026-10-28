package uz.lexikeep

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Html
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: VM) {
    val words by vm.words.collectAsState()
    val reviews by vm.reviews.collectAsState()
    val now = System.currentTimeMillis()
    val start = startOfDay()
    val due = words.count { it.translation.isNotBlank() && it.dueAt <= now }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LexiKeep", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("$due", "Takrorlash kerak", Modifier.weight(1f))
            StatCard("${words.count { it.addedAt >= start }}", "Bugun saqlandi", Modifier.weight(1f))
            StatCard("${streak(reviews)}", "Kunlik seriya", Modifier.weight(1f))
        }
        Text("Bugungi takrorlar: ${reviews.count { it.at >= start }}", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { vm.openLearn() }, Modifier.fillMaxWidth().height(56.dp)) { Text("Mashqni boshlash", fontSize = 18.sp) }
        OutlinedButton(onClick = { vm.screen = Screen.Vocab }, Modifier.fillMaxWidth()) { Text("Saqlangan lug'atim (${words.size})") }
        val ctx = LocalContext.current
        OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, ReaderActivity::class.java)) }, Modifier.fillMaxWidth()) { Text("📂 Fayl ochish va o'qish") }
        Text("Tez qo'shish", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), label = { Text("Inglizcha so'z yoki gap") })
            Button(onClick = { vm.quickAdd(text); text = "" }, enabled = text.isNotBlank()) { Text("Saqlash") }
        }
        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text("Maslahat: boshqa ilovada so'zni belgilang va menyudagi «Lexi» tugmasini bosing.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VocabScreen(vm: VM) {
    val words by vm.words.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var pos by rememberSaveable { mutableStateOf<String?>(null) }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var fav by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf(false) }

    val poss = words.map { it.pos }.filter { it.isNotBlank() }.distinct()
    val topics = words.flatMap { it.topicList() }.distinct().sorted()
    val filtered = words.filter {
        (q.isBlank() || it.text.contains(q, true) || it.translation.contains(q, true)) &&
            (pos == null || it.pos == pos) && (topic == null || it.topicList().contains(topic)) && (!fav || it.favorite)
    }
    val list = when (sort) {
        0 -> filtered.sortedByDescending { it.addedAt }
        1 -> filtered.sortedBy { it.addedAt }
        2 -> filtered.sortedWith(compareBy<Word>({ it.ease }, { -it.lapses }))
        else -> filtered.sortedByDescending { it.lapses }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
            label = { Text("Qidirish (inglizcha yoki o'zbekcha)") })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(pos == null, { pos = null }, { Text("Hammasi") }) }
            items(poss) { p -> FilterChip(pos == p, { pos = if (pos == p) null else p }, { Text(posUz(p)) }) }
        }
        if (topics.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(topics) { t -> FilterChip(topic == t, { topic = if (topic == t) null else t }, { Text("# $t") }) }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(fav, { fav = !fav }, { Text("★ Sevimli") }) }
            val labels = listOf("Yangi", "Eski", "Qiyin", "Ko'p unutilgan")
            items(labels.indices.toList()) { i -> FilterChip(sort == i, { sort = i }, { Text(labels[i]) }) }
        }
        if (sel.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${sel.size} ta tanlandi", Modifier.weight(1f))
                TextButton({ sel = emptySet() }) { Text("Bekor") }
                Button({ confirm = true }) { Text("O'chirish") }
            }
        }
        if (list.isEmpty()) Text("Hozircha so'z yo'q.", Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { w ->
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = {
                            if (sel.isNotEmpty()) { sel = if (w.id in sel) sel - w.id else sel + w.id }
                            else { vm.screen = Screen.Detail(w.id) }
                        },
                        onLongClick = { sel = sel + w.id }),
                    colors = CardDefaults.cardColors(
                        containerColor = if (w.id in sel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(when {
                                w.translation.isNotBlank() -> w.translation
                                w.enrichError.isNotBlank() -> "⚠ ${w.enrichError}"
                                else -> "tarjima kutilmoqda…"
                            })
                            Text("${posUz(w.pos)} · ${w.status()}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (w.favorite) Text("★", fontSize = 20.sp)
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(onDismissRequest = { confirm = false },
            title = { Text("O'chirilsinmi?") }, text = { Text("${sel.size} ta yozuv butunlay o'chiriladi.") },
            confirmButton = { TextButton({ vm.delete(sel.toList()); sel = emptySet(); confirm = false }) { Text("O'chirish") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Bekor") } })
    }
}

@Composable
fun DetailScreen(vm: VM, id: Long) {
    val words by vm.words.collectAsState()
    val reviews by vm.reviews.collectAsState()
    val w = words.find { it.id == id }
    if (w == null) { LaunchedEffect(Unit) { vm.screen = Screen.Vocab }; return }
    var tr by remember(w.id, w.translation) { mutableStateOf(w.translation) }
    var tp by remember(w.id, w.topics) { mutableStateOf(w.topics) }
    var note by remember(w.id) { mutableStateOf(w.note) }
    var confirm by remember { mutableStateOf(false) }
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton({ vm.screen = Screen.Vocab }) { Text("← Orqaga") }
        Text(w.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.speaker.speak(w.text) }) { Text("🔊 Talaffuz") }
            OutlinedButton({ vm.update(w.copy(favorite = !w.favorite)) }) { Text(if (w.favorite) "★ Sevimli" else "☆ Sevimli") }
        }
        if (w.ipa.isNotBlank()) Text(w.ipa)
        Text("${posUz(w.pos)} · ${w.status()}", style = MaterialTheme.typography.labelLarge)
        if (w.translation.isBlank()) {
            Text(if (w.enrichError.isNotBlank()) "⚠ ${w.enrichError}" else "Tarjima hali olinmagan")
            Button({ vm.retry(w.id) }) { Text("Qayta urinish") }
        }
        OutlinedTextField(tr, { tr = it }, Modifier.fillMaxWidth(), label = { Text("O'zbekcha tarjima (tahrirlash mumkin)") })
        if (w.definition.isNotBlank()) Text("Ta'rif: ${w.definition}")
        if (w.example.isNotBlank()) Text("Misol: ${w.example}")
        OutlinedTextField(tp, { tp = it }, Modifier.fillMaxWidth(), label = { Text("Mavzular (vergul bilan: travel, technology)") })
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Shaxsiy izoh") })
        Button({
            val topics = tp.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
            vm.update(w.copy(translation = tr.trim(), topics = topics, note = note, enrichError = ""))
            vm.message = null
        }, Modifier.fillMaxWidth()) { Text("Saqlash") }
        Text("Keyingi takror: ${fmt.format(Date(w.dueAt))}", style = MaterialTheme.typography.bodySmall)
        Text("Takrorlash tarixi", style = MaterialTheme.typography.titleSmall)
        val logs = reviews.filter { it.wordId == w.id }
        if (logs.isEmpty()) Text("Hali yo'q") else logs.take(20).forEach { Text("${fmt.format(Date(it.at))} — ${it.result}") }
        OutlinedButton({ confirm = true }, Modifier.fillMaxWidth()) { Text("O'chirish") }
    }
    if (confirm) {
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text("O'chirilsinmi?") },
            confirmButton = { TextButton({ confirm = false; vm.screen = Screen.Vocab; vm.delete(listOf(w.id)) }) { Text("O'chirish") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Bekor") } })
    }
}
