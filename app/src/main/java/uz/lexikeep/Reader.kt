package uz.lexikeep

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Html
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

fun lexiToast(c: Context, m: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(c, m, Toast.LENGTH_LONG).show() }
}

fun captureText(ctx: Context, raw: String) {
    val app = ctx.applicationContext
    LexiScope.launch {
        when (val r = Lexi.save(app, raw)) {
            is SaveResult.Saved -> {
                lexiToast(app, "✓ Lexi: saqlandi")
                val w = Lexi.enrich(app, r.id)
                if (w != null && w.translation.isNotBlank()) lexiToast(app, "${w.text} — ${w.translation}")
                else lexiToast(app, "Tarjima keyin olinadi")
            }
            SaveResult.Duplicate -> lexiToast(app, "Bu so'z allaqachon saqlangan")
            SaveResult.Empty -> lexiToast(app, "Matn topilmadi")
        }
    }
}

// ---------- Fayldan matn ajratish ----------

object FileText {
    const val MAX = 150_000

    fun name(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: "fayl"
    }

    fun read(ctx: Context, uri: Uri, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Faylni ochib bo'lmadi")
        val raw = ins.use { s ->
            when (ext) {
                "pdf" -> pdf(s)
                "docx" -> docx(s)
                "epub" -> epub(s)
                "html", "htm", "xhtml" -> html(String(limited(s, 3_000_000), Charsets.UTF_8))
                else -> plain(s)
            }
        }
        val t = raw.trim()
        if (t.isBlank()) throw IOException("Faylda o'qiladigan matn topilmadi")
        return if (t.length > MAX) t.substring(0, MAX) + "\n\n[... matn qisqartirildi ...]" else t
    }

    private fun limited(s: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun plain(s: InputStream): String {
        val b = limited(s, 3_000_000)
        if (b.take(4000).any { it.toInt() == 0 }) throw IOException("Bu fayl turi qo'llab-quvvatlanmaydi (matn emas)")
        return String(b, Charsets.UTF_8)
    }

    private fun html(h: String): String =
        Html.fromHtml(h.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), ""), Html.FROM_HTML_MODE_LEGACY)
            .toString().replace("\uFFFC", "")

    private fun pdf(s: InputStream): String {
        val doc = PDDocument.load(s)
        try {
            val n = doc.numberOfPages
            val st = PDFTextStripper()
            st.startPage = 1
            st.endPage = minOf(n, 60)
            val t = st.getText(doc).trim()
            if (t.isBlank()) throw IOException("Bu PDF'da belgilanadigan matn yo'q (skaner/rasm). OCR keyingi bosqichda qo'shiladi.")
            return (if (n > 60) "[Birinchi 60 sahifa ko'rsatilmoqda, jami $n]\n\n" else "") + t
        } finally {
            doc.close()
        }
    }

    private fun docx(s: InputStream): String {
        ZipInputStream(s).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "word/document.xml") {
                    val xml = String(limited(z, 20_000_000), Charsets.UTF_8)
                    return xml.replace("</w:p>", "\u0001").replace("<w:tab/>", "\t").replace("<w:br/>", "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&apos;", "'")
                        .replace("\u0001", "\n")
                }
            }
        }
        throw IOException("DOCX ichida matn topilmadi")
    }

    private fun epub(s: InputStream): String {
        val sb = StringBuilder()
        ZipInputStream(s).use { z ->
            while (sb.length < MAX) {
                val e = z.nextEntry ?: break
                val n = e.name.lowercase()
                if ((n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) && !n.contains("nav") && !n.contains("toc")) {
                    val t = html(String(limited(z, 2_000_000), Charsets.UTF_8)).trim()
                    if (t.isNotEmpty()) sb.append(t).append("\n\n")
                }
            }
        }
        return sb.toString()
    }
}

object Recents {
    private fun p(c: Context) = c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun load(c: Context): List<Pair<String, String>> =
        (p(c).getString("recent", "") ?: "").split("\n").filter { it.contains("\t") }
            .map { val a = it.split("\t"); Pair(a[0], a[1]) }

    fun add(c: Context, u: Uri, name: String) {
        val list = load(c).filter { it.first != u.toString() }.toMutableList()
        list.add(0, Pair(u.toString(), name.replace("\t", " ").replace("\n", " ")))
        p(c).edit().putString("recent", list.take(10).joinToString("\n") { it.first + "\t" + it.second }).apply()
    }
}

// ---------- O'quvchi ekrani ----------

class ReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        val initial: Uri? = intent?.data
        setContent { LexiTheme { ReaderScreen(initial) { finish() } } }
    }
}

fun makeReader(c: Context): ScrollView {
    val d = c.resources.displayMetrics.density
    val tv = TextView(c)
    tv.textSize = 17f
    tv.setLineSpacing(0f, 1.3f)
    tv.setTextIsSelectable(true)
    val p = (8 * d).toInt()
    tv.setPadding(p, p, p, p)
    tv.customSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, 1001, 0, "Lexi")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == 1001) {
                val a = tv.selectionStart.coerceAtLeast(0)
                val b = tv.selectionEnd.coerceAtLeast(0)
                val sel = tv.text.substring(minOf(a, b), maxOf(a, b))
                mode.finish()
                captureText(c, sel)
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }
    val sv = ScrollView(c)
    sv.addView(tv)
    return sv
}

fun bindReader(sv: ScrollView, t: String, fg: Int) {
    val tv = sv.getChildAt(0) as TextView
    tv.setTextColor(fg)
    if (tv.tag !== t) {
        tv.text = t
        tv.tag = t
        sv.scrollTo(0, 0)
    }
}

@Composable
fun ReaderScreen(initial: Uri?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(Recents.load(ctx)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            uri = u
        }
    }
    LaunchedEffect(uri) {
        val u = uri
        if (u != null) {
            loading = true
            error = null
            text = null
            try {
                val nm = FileText.name(ctx, u)
                name = nm
                text = withContext(Dispatchers.IO) { FileText.read(ctx, u, nm) }
                Recents.add(ctx, u, nm)
                recents = Recents.load(ctx)
            } catch (e: Throwable) {
                error = if (e is OutOfMemoryError) "Fayl juda katta" else (e.message ?: "Faylni o'qib bo'lmadi")
            } finally {
                loading = false
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text("← Orqaga") }
                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📂 Fayl tanlash") }
            }
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Fayl o'qilmoqda…")
            }
            error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
            val t = text
            if (t != null) {
                Text(name, fontWeight = FontWeight.Bold)
                Text("So'zni uzoq bosib belgilang va «Lexi» ni tanlang.", style = MaterialTheme.typography.bodySmall)
                val fg = MaterialTheme.colorScheme.onSurface.toArgb()
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { c -> makeReader(c) },
                    update = { sv -> bindReader(sv, t, fg) }
                )
            } else if (!loading) {
                Text("Qo'llab-quvvatlanadi: PDF (matnli), DOCX, EPUB, TXT, MD, HTML, SRT, CSV va boshqa matn fayllar.")
                Text("Rasm yoki skaner qilingan PDF'lar hozircha ochilmaydi (OCR keyingi bosqichda).", style = MaterialTheme.typography.bodySmall)
                if (recents.isNotEmpty()) {
                    Text("Oxirgi ochilganlar", style = MaterialTheme.typography.titleSmall)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        recents.forEach { (u, n) -> TextButton(onClick = { uri = Uri.parse(u) }) { Text(n) } }
                    }
                }
            }
        }
    }
}
