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

// ====================== VIEWMODEL ======================

sealed class Screen {
    object Home : Screen()
    object Vocab : Screen()
    object Stats : Screen()
    object Settings : Screen()
    object Learn : Screen()
    data class Detail(val id: Long) : Screen()
}

class VM(private val app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    val words = dao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val reviews = dao.reviews().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val speaker = Speaker(app)

    var screen by mutableStateOf<Screen>(Screen.Home)
    var message by mutableStateOf<String?>(null)

    // Mashq holati
    var phase by mutableIntStateOf(0)           // 0 sozlash, 1 kartalar, 2 natija
    val queue = mutableStateListOf<Long>()
    var revealed by mutableStateOf(false)
    var initial by mutableIntStateOf(0)
    var knew by mutableIntStateOf(0)
    var wrong by mutableIntStateOf(0)
    var unknown by mutableIntStateOf(0)
    var uzToEn by mutableStateOf(false)
    var learnMsg by mutableStateOf<String?>(null)
    private val cache = HashMap<Long, Word>()

    init { retryPending() }

    fun retryPending() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.pending().forEach { Lexi.enrich(app, it.id) }
        }
    }

    fun quickAdd(raw: String) {
        viewModelScope.launch {
            when (val r = Lexi.save(app, raw)) {
                is SaveResult.Saved -> { message = "Saqlandi. Tarjima olinmoqda..."; val w = Lexi.enrich(app, r.id)
                    message = if (w != null && w.translation.isNotBlank()) "✓ ${w.text} — ${w.translation}" else "Saqlandi, lekin tarjima olinmadi. Keyinroq qayta uriniladi." }
                SaveResult.Duplicate -> message = "Bu so'z allaqachon saqlangan"
                SaveResult.Empty -> message = "Matn bo'sh"
            }
        }
    }

    fun update(w: Word) { viewModelScope.launch { dao.update(w) } }
    fun retry(id: Long) { viewModelScope.launch { Lexi.enrich(app, id) } }
    fun delete(ids: List<Long>) { viewModelScope.launch { dao.deleteReviews(ids); dao.delete(ids) } }

    fun openLearn() { phase = 0; learnMsg = null; screen = Screen.Learn }

    fun startSession(size: Int, forgotten: Boolean, intensive: Boolean) {
        val now = System.currentTimeMillis()
        val pool = words.value.filter { it.translation.isNotBlank() }
        val picked = when {
            forgotten -> pool.filter { it.lastResult == "Adashdim" || it.lastResult == "Bilmadim" }.sortedBy { it.dueAt }
            intensive -> pool.shuffled()
            else -> pool.filter { it.dueAt <= now }.sortedBy { it.dueAt }
        }.take(size.coerceAtLeast(1)).shuffled()
        if (picked.isEmpty()) { learnMsg = "Hozircha mos so'z yo'q. «Hammasini mashq qilish»ni tanlab ko'ring."; return }
        learnMsg = null
        cache.clear(); picked.forEach { cache[it.id] = it }
        queue.clear(); queue.addAll(picked.map { it.id })
        initial = picked.size; knew = 0; wrong = 0; unknown = 0; revealed = false; phase = 1
    }

    fun current(): Word? = queue.firstOrNull()?.let { cache[it] }

    fun answer(a: Answer) {
        val id = queue.firstOrNull() ?: return
        val w = cache[id]
        queue.removeAt(0)
        if (w != null) {
            val now = System.currentTimeMillis()
            val n = Srs.apply(w, a, now)
            cache[id] = n
            viewModelScope.launch { dao.update(n); dao.addReview(Review(wordId = id, result = a.label, at = now)) }
            when (a) {
                Answer.KNEW -> knew++
                Answer.WRONG -> { wrong++; queue.add(minOf(5, queue.size), id) }      // bir necha kartadan keyin qaytadi
                Answer.UNKNOWN -> { unknown++; queue.add(minOf(3, queue.size), id) }
            }
        }
        revealed = false
        if (queue.isEmpty()) phase = 2
    }

    override fun onCleared() { speaker.shutdown() }
}

// ====================== "LEXI" BUYRUG'I (matn belgilash menyusi + Ulashish) ======================

class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString() ?: ""
        val app = applicationContext
        LexiScope.launch {
            when (val r = Lexi.save(app, raw)) {
                is SaveResult.Saved -> {
                    toast(app, "✓ Lexi: saqlandi")
                    val w = Lexi.enrich(app, r.id)
                    if (w != null && w.translation.isNotBlank()) toast(app, "${w.text} — ${w.translation}")
                    else toast(app, "Tarjima keyin olinadi (internet/xizmat yo'q)")
                }
                SaveResult.Duplicate -> toast(app, "Bu so'z allaqachon saqlangan")
                SaveResult.Empty -> toast(app, "Matn topilmadi")
            }
        }
        setResult(RESULT_CANCELED)
        finish()
    }
    private fun toast(c: Context, m: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(c, m, Toast.LENGTH_LONG).show() }
    }
}

// ====================== ILOVA INTERFEYSI ======================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LexiTheme { App() } }
    }
}

@Composable
fun LexiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: VM = viewModel()) {
    val screen = vm.screen
    BackHandler(enabled = screen != Screen.Home) {
        vm.screen = if (screen is Screen.Detail) Screen.Vocab else Screen.Home
    }
    val tabs = listOf<Triple<Screen, String, ImageVector>>(
        Triple(Screen.Home, "Bosh", Icons.Default.Home),
        Triple(Screen.Vocab, "Lug'at", Icons.Default.Search),
        Triple(Screen.Stats, "Statistika", Icons.Default.Star),
        Triple(Screen.Settings, "Sozlamalar", Icons.Default.Settings)
    )
    Scaffold(
        bottomBar = {
            if (screen !is Screen.Detail && screen !is Screen.Learn) {
                NavigationBar {
                    tabs.forEach { (s, l, ic) ->
                        NavigationBarItem(selected = screen == s, onClick = { vm.screen = s },
                            icon = { Icon(ic, contentDescription = l) }, label = { Text(l) })
                    }
                }
            }
        }
    ) { pad ->
        Surface(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.Vocab -> VocabScreen(vm)
                Screen.Stats -> StatsScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Learn -> LearnScreen(vm)
                is Screen.Detail -> DetailScreen(vm, screen.id)
            }
        }
    }
}

fun startOfDay(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun streak(reviews: List<Review>): Int {
    val zone = ZoneId.systemDefault()
    val days = reviews.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.toSet()
    var d = LocalDate.now()
    if (d !in days) d = d.minusDays(1)
    var n = 0
    while (d in days) { n++; d = d.minusDays(1) }
    return n
}

@Composable
fun StatCard(n: String, label: String, m: Modifier) {
    Card(m) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(n, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}
