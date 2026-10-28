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
fun LearnScreen(vm: VM) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton({ vm.screen = Screen.Home }) { Text("← Orqaga") }
        when (vm.phase) { 0 -> LearnSetup(vm); 1 -> LearnCards(vm); else -> LearnResult(vm) }
    }
}

@Composable
fun LearnSetup(vm: VM) {
    val words by vm.words.collectAsState()
    var size by remember { mutableStateOf("10") }
    var forgotten by remember { mutableStateOf(false) }
    var intensive by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val due = words.count { it.translation.isNotBlank() && it.dueAt <= now }
    Text("Mashq", style = MaterialTheme.typography.headlineSmall)
    Text("Takrorlashga tayyor: $due ta")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!vm.uzToEn, { vm.uzToEn = false }, { Text("English → O'zbek") })
        FilterChip(vm.uzToEn, { vm.uzToEn = true }, { Text("O'zbek → English") })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(5, 10, 20, 50).forEach { n -> FilterChip(size == "$n", { size = "$n" }, { Text("$n") }) }
    }
    OutlinedTextField(size, { size = it.filter(Char::isDigit).take(3) }, label = { Text("Maxsus son") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(forgotten, { forgotten = !forgotten; if (forgotten) intensive = false }, { Text("Faqat unutilganlar") })
        FilterChip(intensive, { intensive = !intensive; if (intensive) forgotten = false }, { Text("Hammasini mashq qilish") })
    }
    vm.learnMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button({ vm.startSession(size.toIntOrNull() ?: 10, forgotten, intensive) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Boshlash") }
}

@Composable
fun LearnCards(vm: VM) {
    val w = vm.current()
    if (w == null) { Text("Yuklanmoqda…"); return }
    val unique = vm.queue.distinct().size
    LinearProgressIndicator(progress = { (vm.initial - unique).toFloat() / vm.initial }, Modifier.fillMaxWidth())
    Text("${vm.initial - unique} / ${vm.initial}", style = MaterialTheme.typography.labelMedium)
    val front = if (vm.uzToEn) w.translation else w.text
    Card(Modifier.fillMaxWidth().heightIn(min = 280.dp).clickable { vm.revealed = true }) {
        Column(Modifier.padding(24.dp).fillMaxWidth().heightIn(min = 280.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(front, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (!vm.revealed) {
                Spacer(Modifier.height(16.dp))
                Text("Ma'nosini o'ylang, so'ng kartani bosing", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            } else {
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Text(if (vm.uzToEn) w.text else w.translation, fontSize = 24.sp, textAlign = TextAlign.Center)
                if (w.ipa.isNotBlank()) Text(w.ipa)
                if (w.definition.isNotBlank()) Text(w.definition, textAlign = TextAlign.Center)
                if (w.example.isNotBlank()) Text("“${w.example}”", textAlign = TextAlign.Center)
            }
        }
    }
    if (!vm.uzToEn || vm.revealed) TextButton({ vm.speaker.speak(w.text) }) { Text("🔊 Talaffuz") }
    if (vm.revealed) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.answer(Answer.KNEW) }, Modifier.weight(1f)) { Text("Bildim") }
            FilledTonalButton({ vm.answer(Answer.WRONG) }, Modifier.weight(1f)) { Text("Adashdim") }
            OutlinedButton({ vm.answer(Answer.UNKNOWN) }, Modifier.weight(1f)) { Text("Bilmadim") }
        }
        Text("«Bildim» — javobni ko'rishdan oldin haqiqatan bilgan bo'lsangizgina bosing.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun LearnResult(vm: VM) {
    val total = vm.knew + vm.wrong + vm.unknown
    Text("Mashq tugadi 🎉", style = MaterialTheme.typography.headlineSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("${vm.knew}", "Bildim", Modifier.weight(1f))
        StatCard("${vm.wrong}", "Adashdim", Modifier.weight(1f))
        StatCard("${vm.unknown}", "Bilmadim", Modifier.weight(1f))
    }
    Text("Aniqlik: ${if (total == 0) 0 else vm.knew * 100 / total}%")
    Button({ vm.phase = 0 }, Modifier.fillMaxWidth()) { Text("Yana mashq") }
    OutlinedButton({ vm.screen = Screen.Home }, Modifier.fillMaxWidth()) { Text("Bosh sahifa") }
}

@Composable
fun StatsScreen(vm: VM) {
    val words by vm.words.collectAsState()
    val reviews by vm.reviews.collectAsState()
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val counts = days.map { d -> reviews.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == d } }
    val acc = if (reviews.isEmpty()) 0 else reviews.count { it.result == "Bildim" } * 100 / reviews.size
    val barColor = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Statistika", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${words.size}", "Jami so'z", Modifier.weight(1f))
            StatCard("${words.count { it.status() == "O'rganilmoqda" }}", "O'rganilmoqda", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${words.count { it.status() == "Yodda" }}", "Yodda qolgan", Modifier.weight(1f))
            StatCard("${words.count { it.translation.isNotBlank() && it.dueAt <= now }}", "Takrorlash kerak", Modifier.weight(1f))
        }
        Text("Umumiy aniqlik: $acc%  ·  Jami takror: ${reviews.size}")
        Text("Oxirgi 7 kun faolligi", style = MaterialTheme.typography.titleSmall)
        val mx = maxOf(1, counts.maxOrNull() ?: 1)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val bw = size.width / 7f
            counts.forEachIndexed { i, c ->
                val h = size.height * c / mx
                drawRect(barColor, Offset(i * bw + bw * 0.15f, size.height - h), Size(bw * 0.7f, h))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { i, d -> Text("${d.dayOfMonth}\n${counts[i]}", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
fun SettingsScreen(vm: VM) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sozlamalar", style = MaterialTheme.typography.headlineSmall)
        Text("«Lexi» buyrug'i", style = MaterialTheme.typography.titleSmall)
        Text("Chrome, PDF o'quvchi, Telegram va boshqa ilovalarda so'zni uzoq bosib belgilang. Chiqqan menyuda " +
            "«Nusxa olish» yonidan «Lexi» ni toping (ba'zan «⋮» / «Ko'proq» ichida bo'ladi). Bosganingizda so'z shu ilovaga saqlanadi.\n\n" +
            "Belgilashga ruxsat bermaydigan ilovalarda: matnni «Ulashish» → «Lexi» orqali yuboring.")
        Text("Maxfiylik", style = MaterialTheme.typography.titleSmall)
        Text("Ilova faqat siz «Lexi»ni bosganingizda matnni oladi. Ekran kuzatilmaydi, maxsus ruxsat (accessibility/overlay) ishlatilmaydi. " +
            "Tarjima uchun faqat saqlangan so'z/gap internetdagi bepul tarjima va lug'at xizmatlariga yuboriladi. " +
            "Ma'lumotlar telefoningizda saqlanadi.")
        Button({ vm.retryPending(); vm.message = "Tarjimasiz so'zlar qayta tekshirilmoqda…" }, Modifier.fillMaxWidth()) {
            Text("Tarjimasiz so'zlarni qayta urinish")
        }
        vm.message?.let { Text(it) }
    }
}
