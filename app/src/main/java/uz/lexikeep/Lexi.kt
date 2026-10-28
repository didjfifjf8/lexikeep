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

// ====================== MA'LUMOTLAR BAZASI ======================

@Entity(tableName = "words", indices = [Index(value = ["textKey"], unique = true)])
data class Word(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val textKey: String,
    val translation: String = "",
    val definition: String = "",
    val ipa: String = "",
    val audioUrl: String = "",
    val pos: String = "",
    val example: String = "",
    val topics: String = "",
    val note: String = "",
    val favorite: Boolean = false,
    val addedAt: Long,
    val ease: Double = 2.5,
    val intervalDays: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val dueAt: Long,
    val lastResult: String = "",
    val enrichError: String = ""
)

@Entity(tableName = "reviews")
data class Review(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val result: String,
    val at: Long
)

fun Word.topicList(): List<String> = topics.split(",").map { it.trim() }.filter { it.isNotEmpty() }
fun Word.status(): String = when {
    lastResult.isBlank() -> "Yangi"
    intervalDays >= 21 && reps >= 3 -> "Yodda"
    else -> "O'rganilmoqda"
}
fun posUz(p: String): String = when (p) {
    "noun" -> "Ot"; "verb" -> "Fe'l"; "adjective" -> "Sifat"; "adverb" -> "Ravish"
    "phrase" -> "Ibora"; "sentence" -> "Gap"; "" -> "Boshqa"; else -> p
}

@Dao
interface WordDao {
    @Query("SELECT * FROM words ORDER BY addedAt DESC") fun all(): Flow<List<Word>>
    @Query("SELECT * FROM words WHERE textKey = :k LIMIT 1") suspend fun byKey(k: String): Word?
    @Query("SELECT * FROM words WHERE id = :id") suspend fun byId(id: Long): Word?
    @Query("SELECT * FROM words WHERE translation = '' OR enrichError != ''") suspend fun pending(): List<Word>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(w: Word): Long
    @Update suspend fun update(w: Word)
    @Query("DELETE FROM words WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("DELETE FROM reviews WHERE wordId IN (:ids)") suspend fun deleteReviews(ids: List<Long>)
    @Insert suspend fun addReview(r: Review)
    @Query("SELECT * FROM reviews ORDER BY at DESC") fun reviews(): Flow<List<Review>>
}

@Database(entities = [Word::class, Review::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): WordDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "lexikeep.db").build().also { inst = it }
        }
    }
}

// ====================== TARMOQ (haqiqiy xizmatlar, kalit shart emas) ======================

class DictInfo(val ipa: String, val audio: String, val pos: String, val def: String, val example: String)

object Net {
    private fun get(u: String): Pair<Int, String> {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "LexiKeep/1.0")
        try {
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            return Pair(code, s)
        } finally {
            c.disconnect()
        }
    }

    /** MyMemory: bepul tarjima xizmati (en -> uz). Xato bo'lsa IOException tashlaydi, hech narsa to'qilmaydi. */
    fun translate(t: String): String {
        val (code, body) = get("https://api.mymemory.translated.net/get?q=${URLEncoder.encode(t, "UTF-8")}&langpair=en%7Cuz")
        if (code != 200) throw IOException("Tarjima xizmati javob bermadi ($code)")
        val j = JSONObject(body)
        val txt = j.getJSONObject("responseData").optString("translatedText", "")
        if (j.optInt("responseStatus", 200) != 200 || txt.isBlank() || txt.startsWith("MYMEMORY WARNING")) {
            throw IOException("Tarjima olinmadi (kunlik limit tugagan bo'lishi mumkin)")
        }
        return Html.fromHtml(txt, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    /** dictionaryapi.dev: ta'rif, IPA, so'z turi, misol. So'z topilmasa null. */
    fun dictionary(w: String): DictInfo? {
        val (code, body) = get("https://api.dictionaryapi.dev/api/v2/entries/en/${URLEncoder.encode(w, "UTF-8")}")
        if (code == 404) return null
        if (code != 200) throw IOException("Lug'at xizmati javob bermadi ($code)")
        val e = JSONArray(body).getJSONObject(0)
        var ipa = e.optString("phonetic", "")
        var audio = ""
        e.optJSONArray("phonetics")?.let {
            for (i in 0 until it.length()) {
                val p = it.getJSONObject(i)
                if (ipa.isBlank()) ipa = p.optString("text", "")
                if (audio.isBlank()) audio = p.optString("audio", "")
            }
        }
        val m = e.optJSONArray("meanings")?.optJSONObject(0)
        val d = m?.optJSONArray("definitions")?.optJSONObject(0)
        return DictInfo(ipa, audio, m?.optString("partOfSpeech", "") ?: "", d?.optString("definition", "") ?: "", d?.optString("example", "") ?: "")
    }
}

sealed class SaveResult {
    data class Saved(val id: Long) : SaveResult()
    object Duplicate : SaveResult()
    object Empty : SaveResult()
}

val LexiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

object Lexi {
    /** Avval mahalliy saqlaydi (internet shart emas), dublikatni aniqlaydi. */
    suspend fun save(ctx: Context, raw: String): SaveResult = withContext(Dispatchers.IO) {
        var t = raw.trim().replace(Regex("\\s+"), " ").take(500)
        if (!t.contains(' ')) t = t.trim { !it.isLetterOrDigit() }
        if (t.isBlank()) return@withContext SaveResult.Empty
        val dao = AppDb.get(ctx).dao()
        val key = t.lowercase()
        if (dao.byKey(key) != null) return@withContext SaveResult.Duplicate
        val now = System.currentTimeMillis()
        val n = t.split(' ').size
        val pos = if (n == 1) "" else if (n <= 3) "phrase" else "sentence"
        val id = dao.insert(Word(text = t, textKey = key, pos = pos, addedAt = now, dueAt = now))
        if (id == -1L) SaveResult.Duplicate else SaveResult.Saved(id)
    }

    /** Tarjima va lug'at ma'lumotini olib to'ldiradi. Xato bo'lsa enrichError'ga yozadi. */
    suspend fun enrich(ctx: Context, id: Long): Word? = withContext(Dispatchers.IO) {
        val dao = AppDb.get(ctx).dao()
        var w = dao.byId(id) ?: return@withContext null
        var err = ""
        if (w.translation.isBlank()) {
            try { w = w.copy(translation = Net.translate(w.text)) } catch (e: Exception) { err = e.message ?: "Tarmoq xatosi" }
        }
        if (!w.text.contains(' ') && w.definition.isBlank()) {
            try {
                Net.dictionary(w.text)?.let { d ->
                    w = w.copy(definition = d.def, ipa = d.ipa, audioUrl = d.audio, example = d.example, pos = w.pos.ifBlank { d.pos })
                }
            } catch (e: Exception) { if (err.isEmpty()) err = e.message ?: "Tarmoq xatosi" }
        }
        val latest = dao.byId(id) ?: return@withContext null
        val merged = latest.copy(
            translation = latest.translation.ifBlank { w.translation },
            definition = latest.definition.ifBlank { w.definition },
            ipa = latest.ipa.ifBlank { w.ipa },
            audioUrl = latest.audioUrl.ifBlank { w.audioUrl },
            example = latest.example.ifBlank { w.example },
            pos = latest.pos.ifBlank { w.pos },
            enrichError = err
        )
        dao.update(merged)
        merged
    }
}

// ====================== TAKRORLASH ALGORITMI (SM-2 asosida) ======================

enum class Answer(val label: String) { KNEW("Bildim"), WRONG("Adashdim"), UNKNOWN("Bilmadim") }

object Srs {
    private const val DAY = 86_400_000L
    fun apply(w: Word, a: Answer, now: Long): Word = when (a) {
        Answer.KNEW -> {
            val iv = when (w.reps) { 0 -> 1.0; 1 -> 3.0; else -> maxOf(1.0, w.intervalDays) * w.ease }
            w.copy(reps = w.reps + 1, intervalDays = iv, ease = minOf(3.0, w.ease + 0.1),
                dueAt = now + (iv * DAY).toLong(), lastResult = a.label)
        }
        Answer.WRONG -> {
            val iv = maxOf(1.0, Math.round(w.intervalDays * 0.3).toDouble())
            w.copy(reps = 0, lapses = w.lapses + 1, intervalDays = iv, ease = maxOf(1.3, w.ease - 0.2),
                dueAt = now + (iv * DAY).toLong(), lastResult = a.label)
        }
        Answer.UNKNOWN -> w.copy(reps = 0, lapses = w.lapses + 1, intervalDays = 0.0, ease = maxOf(1.3, w.ease - 0.3),
            dueAt = now + 10 * 60_000L, lastResult = a.label)
    }
}

// ====================== TALAFFUZ ======================

class Speaker(ctx: Context) {
    private var ready = false
    private var tts: TextToSpeech? = null
    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) { tts?.language = Locale.US; ready = true }
        }
    }
    fun speak(t: String) { if (ready) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "lexi") }
    fun shutdown() { tts?.shutdown() }
}

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
                Answer.WRONG -> { wrong++; queue.add(minOf(5, queue.size), id) }
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
    var n =
