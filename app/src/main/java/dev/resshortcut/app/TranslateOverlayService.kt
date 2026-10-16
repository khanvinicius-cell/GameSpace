package dev.resshortcut.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bolha flutuante: toque = traduzir a tela; toque no resultado = fechar; segurar = abrir o app. */
class TranslateOverlayService : Service() {

    data class Block(val text: String, val rect: Rect)
    data class Item(val rect: Rect, val text: String)
    data class GResult(val text: String, val lang: String)

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "dev.resshortcut.app.TRANSLATE_STOP"
        private const val CHANNEL_ID = "vykat"
        private const val NOTIF_ID = 7
        private const val MAX_BLOCKS = 40
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val pool = Executors.newFixedThreadPool(4)

    private var bubble: TextView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var resultView: View? = null
    private var selectorView: View? = null
    private var modeMenuView: View? = null
    private var modeMenuParams: WindowManager.LayoutParams? = null
    private var pendingSingleTap: Runnable? = null
    @Volatile private var busy = false
    @Volatile private var automaticRunning = false
    private var selectedRegion: Rect? = null
    private var lastFrameHash: Int? = null
    private val translationCache = LinkedHashMap<String, String>()
    private val processedCache = LinkedHashSet<String>()
    private val mlTranslators = HashMap<String, Translator>()
    private val readyModels = HashSet<String>()
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }
    private val autoIntervalMs = 2200L
    private val autoRunnable = object : Runnable {
        override fun run() { runAutomaticCycle() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification())
        if (!Settings.canDrawOverlays(this)) {
            toast("Permita a sobreposição de tela no Vyka T.")
            stopSelf()
            return START_NOT_STICKY
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (bubble == null) createBubble()
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopAutomaticMonitor(showToast = false)
        pendingSingleTap?.let { main.removeCallbacks(it) }
        dismissModeMenu()
        dismissResult()
        removeSelector()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        mlTranslators.values.forEach { try { it.close() } catch (_: Exception) {} }
        mlTranslators.clear()
        readyModels.clear()
        try { languageIdentifier.close() } catch (_: Exception) {}
        exec.shutdownNow()
        pool.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ notificação

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bolha de tradução", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, TranslateOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Vyka T")
            .setContentText("Toque para traduzir; toque duas vezes para abrir os modos e idiomas.")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Parar",
                    stop
                ).build()
            )
            .build()
    }

    // ------------------------------------------------------------ bolha

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createBubble() {
        val size = dp(52)
        val tv = TextView(this).apply {
            text = "文A"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
                setStroke(dp(2), 0xFF6FCF97.toInt())
            }
        }
        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.bubbleX(this@TranslateOverlayService, dp(8))
            y = Prefs.bubbleY(this@TranslateOverlayService, dp(220))
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L
        var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    downTime = e.eventTime
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) moved = true
                    if (moved) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, bubbleParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        Prefs.setBubble(this, bubbleParams.x, bubbleParams.y)
                    } else if (e.eventTime - downTime > 600) {
                        openSettings()
                    } else {
                        handleBubbleTap()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(tv, bubbleParams)
        bubble = tv
    }

    private fun bringBubbleToFront() {
        val b = bubble ?: return
        try {
            wm.removeView(b)
            wm.addView(b, bubbleParams)
        } catch (_: Exception) {}
    }

    private fun setBubbleText(s: String) {
        bubble?.text = s
    }

    private fun openSettings() {
        startActivity(Intent(this, TranslateSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun handleBubbleTap() {
        if (pendingSingleTap != null) {
            pendingSingleTap?.let { main.removeCallbacks(it) }
            pendingSingleTap = null
            showModeMenu()
            return
        }
        val action = Runnable {
            pendingSingleTap = null
            onBubbleSingleTap()
        }
        pendingSingleTap = action
        main.postDelayed(action, 280L)
    }

    private fun onBubbleSingleTap() {
        when (Prefs.mode(this)) {
            2, 3 -> {
                if (automaticRunning) stopAutomaticMonitor()
                else if (Prefs.mode(this) == 2 && selectedRegion == null) translateScreen()
                else startAutomaticMonitor()
            }
            else -> if (resultView != null) dismissResult() else translateScreen()
        }
    }

    private fun showModeMenu() {
        dismissModeMenu()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF21A1A1A.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF6FCF97.toInt())
            }
        }
        fun addLabel(text: String) {
            root.addView(TextView(this).apply {
                this.text = text; textSize = 12f; setTextColor(0xFFBDBDBD.toInt())
                setPadding(dp(4), dp(5), dp(4), dp(2))
            })
        }
        fun addModeButton(index: Int) {
            val selected = Prefs.mode(this) == index
            root.addView(TextView(this).apply {
                text = (if (selected) "✓  " else "   ") + Langs.modes[index]
                textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (selected) 0xFF315943.toInt() else 0xFF292929.toInt())
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    stopAutomaticMonitor(showToast = false)
                    Prefs.setMode(this@TranslateOverlayService, index)
                    selectedRegion = null
                    lastFrameHash = null
                    dismissModeMenu()
                    main.post { setBubbleText(if (index == 2 || index == 3) "AUTO" else "文A") }
                    toast("Modo: ${Langs.modes[index]}")
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        addLabel("MODO DE TRADUÇÃO")
        Langs.modes.indices.forEach { addModeButton(it) }
        addLabel("TRADUZIR DE")
        root.addView(languageSpinner(Langs.languages, Prefs.source(this)) { Prefs.setSource(this, it) })
        addLabel("TRADUZIR PARA")
        root.addView(languageSpinner(Langs.targets, Prefs.target(this)) { Prefs.setTarget(this, it) })
        root.addView(TextView(this).apply {
            text = "Fechar"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(0xFF6FCF97.toInt()); setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { dismissModeMenu() }
        })

        val metrics = resources.displayMetrics
        val width = dp(248)
        val height = dp(440).coerceAtMost(metrics.heightPixels - dp(24))
        val b = bubbleParams
        val xRight = b.x + dp(52) + dp(8)
        val x = if (xRight + width <= metrics.widthPixels) xRight else max(dp(4), b.x - width - dp(8))
        val y = b.y.coerceIn(dp(4), max(dp(4), metrics.heightPixels - height - dp(4)))
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        try {
            wm.addView(root, params)
            modeMenuView = root
            modeMenuParams = params
            bringBubbleToFront()
        } catch (e: Exception) { toast("Não consegui abrir o menu: ${e.message}") }
    }

    private fun languageSpinner(languages: List<Pair<String, String>>, current: String, save: (String) -> Unit): Spinner {
        return Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@TranslateOverlayService, android.R.layout.simple_spinner_item,
                languages.map { it.first }) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE); setPadding(dp(6), dp(8), dp(6), dp(8))
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE); setPadding(dp(12), dp(10), dp(12), dp(10))
                        setBackgroundColor(0xFF242424.toInt())
                    }
            }
            setBackgroundColor(0xFF292929.toInt())
            setPopupBackgroundDrawable(GradientDrawable().apply { setColor(0xFF242424.toInt()) })
            setSelection(languages.indexOfFirst { it.second == current }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (languages[position].second != current) save(languages[position].second)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
    }

    private fun dismissModeMenu() {
        modeMenuView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        modeMenuView = null
        modeMenuParams = null
    }

    // ------------------------------------------------------------ fluxo principal

    private fun translateScreen() {
        if (busy) return
        busy = true
        bubble?.visibility = View.INVISIBLE   // some da captura
        exec.execute {
            try {
                Thread.sleep(220)
                if (!ShizukuShell.isReady()) {
                    throw IllegalStateException("Shizuku não está em execução ou autorizado.")
                }
                val png = ShizukuShell.execBytes("screencap -p")
                val decoded = if (png.size > 1000) BitmapFactory.decodeByteArray(png, 0, png.size) else null
                val bmp = decoded ?: throw IllegalStateException("Não consegui capturar a tela.")
                main.post {
                    bubble?.visibility = View.VISIBLE
                    setBubbleText("…")
                }

                val mode = Prefs.mode(this@TranslateOverlayService)
                if (mode == 1 || (mode == 2 && selectedRegion == null)) {
                    main.post { showRegionSelector(bmp) }
                    return@execute
                }
                if (mode == 2 || mode == 3) {
                    main.post { startAutomaticMonitor() }
                    return@execute
                }
                processBitmap(bmp, null)
            } catch (e: Exception) {
                toast("Falha: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                main.post {
                    bubble?.visibility = View.VISIBLE
                    setBubbleText("文A")
                }
            }
        }
    }

    private fun processBitmap(bmp: Bitmap, offset: Pair<Int, Int>?, ocrBitmap: Bitmap = bmp) {
        try {
            val blocks = ocr(ocrBitmap).map { block ->
                if (offset == null) block else Block(block.text, Rect(block.rect).apply {
                    offset(offset.first, offset.second)
                })
            }
            if (blocks.isEmpty()) {
                toast("Nenhum texto encontrado. Confira o alfabeto escolhido no app.")
                return
            }
            val items = translateBlocks(blocks, forceOverlay = true)
            if (items.isEmpty()) {
                toast("Nada a traduzir (o texto já pode estar no idioma de destino).")
                return
            }
            main.post { showResult(bmp, items) }
        } catch (e: Exception) {
            toast("Falha: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
            main.post { bubble?.visibility = View.VISIBLE; setBubbleText("文A") }
        }
    }

    private fun showRegionSelector(bmp: Bitmap) {
        dismissResult()
        val metrics = resources.displayMetrics
        val scaleX = bmp.width.toFloat() / metrics.widthPixels.coerceAtLeast(1)
        val scaleY = bmp.height.toFloat() / metrics.heightPixels.coerceAtLeast(1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA4CAF50.toInt(); style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
        }
        val selector = object : View(this) {
            var sx = 0f; var sy = 0f; var ex = 0f; var ey = 0f
            var selecting = false
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawColor(0x66000000)
                if (selecting || (ex > sx && ey > sy)) {
                    val r = RectFCompat(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))
                    canvas.drawRect(r.left, r.top, r.right, r.bottom, paint)
                    canvas.drawRect(r.left, r.top, r.right, r.bottom, stroke)
                }
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { sx = event.x; sy = event.y; ex = sx; ey = sy; selecting = true; invalidate(); return true }
                    MotionEvent.ACTION_MOVE -> { ex = event.x; ey = event.y; invalidate(); return true }
                    MotionEvent.ACTION_UP -> {
                        ex = event.x; ey = event.y; selecting = false
                        val left = (minOf(sx, ex) * scaleX).toInt().coerceIn(0, bmp.width - 1)
                        val top = (minOf(sy, ey) * scaleY).toInt().coerceIn(0, bmp.height - 1)
                        val right = (maxOf(sx, ex) * scaleX).toInt().coerceIn(left + 1, bmp.width)
                        val bottom = (maxOf(sy, ey) * scaleY).toInt().coerceIn(top + 1, bmp.height)
                        removeSelector()
                        if (right - left < dp(20) || bottom - top < dp(12)) {
                            busy = false; toast("Selecione uma área maior."); return true
                        }
                        val crop = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
                        if (Prefs.mode(this@TranslateOverlayService) == 2) {
                            selectedRegion = Rect(left, top, right, bottom)
                            crop.recycle()
                            lastFrameHash = null
                            busy = false
                            toast("Região definida. Monitoramento automático iniciado.")
                            startAutomaticMonitor()
                        } else {
                            exec.execute { processBitmap(bmp, Pair(left, top), crop) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> { removeSelector(); busy = false; return true }
                }
                return true
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(selector, params)
            selectorView = selector
            toast("Arraste o dedo para selecionar a região de texto.")
        } catch (e: Exception) {
            busy = false
            toast("Não consegui abrir a seleção: ${e.message}")
        }
    }

    private fun removeSelector() {
        selectorView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        selectorView = null
    }

    private data class RectFCompat(val left: Float, val top: Float, val right: Float, val bottom: Float)

    // ------------------------------------------------------------ tradução automática eficiente

    private fun startAutomaticMonitor() {
        if (automaticRunning) return
        automaticRunning = true
        lastFrameHash = null
        main.post { setBubbleText("AUTO") }
        toast(if (Prefs.mode(this) == 2) "Monitorando a região selecionada." else "Monitorando a tela inteira.")
        main.removeCallbacks(autoRunnable)
        main.post(autoRunnable)
    }

    private fun stopAutomaticMonitor(showToast: Boolean = true) {
        val wasRunning = automaticRunning
        automaticRunning = false
        main.removeCallbacks(autoRunnable)
        main.post {
            if (bubble != null) setBubbleText("文A")
            resultView?.visibility = View.VISIBLE
        }
        if (showToast && wasRunning) toast("Tradução automática pausada.")
    }

    private fun frameFingerprint(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        try {
            val pixels = IntArray(48 * 48)
            small.getPixels(pixels, 0, 48, 0, 0, 48, 48)
            var hash = 1
            for (pixel in pixels) hash = 31 * hash + (pixel ushr 5)
            return hash
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    private fun blockCacheKey(text: String): String =
        "${Prefs.engine(this)}|${Prefs.source(this)}|${Prefs.target(this)}|${text.trim()}"

    private fun rectKey(rect: Rect): String = "${rect.left},${rect.top},${rect.right},${rect.bottom}"

    private fun runAutomaticCycle() {
        if (!automaticRunning || busy) {
            if (automaticRunning) main.postDelayed(autoRunnable, autoIntervalMs)
            return
        }
        busy = true
        exec.execute {
            var resultHandled = false
            var screenToRecycle: Bitmap? = null
            try {
                main.post { bubble?.visibility = View.INVISIBLE; resultView?.visibility = View.INVISIBLE }
                Thread.sleep(140)
                if (!ShizukuShell.isReady()) throw IllegalStateException("Shizuku não está em execução ou autorizado.")
                val png = ShizukuShell.execBytes("screencap -p")
                val screen = BitmapFactory.decodeByteArray(png, 0, png.size)
                    ?: throw IllegalStateException("Não consegui capturar a tela.")
                val region = if (Prefs.mode(this) == 2) selectedRegion else null
                val ocrBitmap = if (region != null) {
                    val safe = Rect(region).apply {
                        left = left.coerceIn(0, screen.width - 1); top = top.coerceIn(0, screen.height - 1)
                        right = right.coerceIn(left + 1, screen.width); bottom = bottom.coerceIn(top + 1, screen.height)
                    }
                    Bitmap.createBitmap(screen, safe.left, safe.top, safe.width(), safe.height())
                } else screen
                screenToRecycle = screen
                val fingerprint = frameFingerprint(ocrBitmap)
                if (lastFrameHash != fingerprint) {
                    lastFrameHash = fingerprint
                    val found = ocr(ocrBitmap).map { block ->
                        if (region == null) block else Block(block.text, Rect(block.rect).apply { offset(region.left, region.top) })
                    }
                    val missing = found.filter { blockCacheKey(it.text) !in processedCache }
                    if (missing.isNotEmpty()) {
                        val translated = translateBlocks(missing)
                        for (item in translated) {
                            val sourceBlock = missing.firstOrNull { rectKey(it.rect) == rectKey(item.rect) }
                            if (sourceBlock != null) translationCache[blockCacheKey(sourceBlock.text)] = item.text
                        }
                        missing.forEach { processedCache.add(blockCacheKey(it.text)) }
                        while (processedCache.size > 250) {
                            val oldest = processedCache.first(); processedCache.remove(oldest); translationCache.remove(oldest)
                        }
                    }
                    val currentItems = found.mapNotNull { block ->
                        translationCache[blockCacheKey(block.text)]?.let { Item(block.rect, it) }
                    }
                    resultHandled = true
                    main.post {
                        if (automaticRunning) {
                            if (currentItems.isEmpty()) dismissResult() else showResult(screen, currentItems)
                        }
                    }
                }
                if (ocrBitmap !== screen) ocrBitmap.recycle()
            } catch (e: Exception) {
                lastFrameHash = null // permite tentar novamente se a captura/OCR falhar
                if (automaticRunning) toast("Automática: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                main.post {
                    bubble?.visibility = View.VISIBLE
                    if (!automaticRunning || !resultHandled) resultView?.visibility = View.VISIBLE
                    if (automaticRunning) main.postDelayed(autoRunnable, autoIntervalMs)
                    screenToRecycle?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                }
            }
        }
    }

    // ------------------------------------------------------------ OCR

    private fun ocr(bmp: Bitmap): List<Block> {
        val options: TextRecognizerOptionsInterface = when (Prefs.script(this)) {
            1 -> JapaneseTextRecognizerOptions.Builder().build()
            2 -> ChineseTextRecognizerOptions.Builder().build()
            3 -> KoreanTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        }
        val recognizer = TextRecognition.getClient(options)
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)
            val detected = if (Prefs.mode(this) == 2 || Prefs.mode(this) == 3) {
                // Modo automático de região: linhas separadas para reduzir a mistura entre caixas de texto.
                text.textBlocks.flatMap { block -> block.lines }
                    .mapNotNull { line ->
                        val r = line.boundingBox
                        val t = line.text.trim()
                        if (r == null || t.length < 2 || t.none { it.isLetter() }) null else Block(t, r)
                    }
            } else {
                text.textBlocks.mapNotNull { block ->
                    val r = block.boundingBox
                    val t = block.text.replace('\n', ' ').trim()
                    if (r == null || t.length < 2 || t.none { it.isLetter() }) null else Block(t, r)
                }
            }
            return detected.take(MAX_BLOCKS)
        } finally {
            recognizer.close()
        }
    }

    // ------------------------------------------------------------ tradução

    private fun translateBlocks(blocks: List<Block>, forceOverlay: Boolean = false): List<Item> {
        val target = Prefs.target(this)
        val cleaned = blocks.map { b ->
            // Protege os símbolos para que o motor não os interprete nem os reproduza literalmente.
            Block(b.text.replace("/", " ZXQCHAINBREAKZXQ ").replace("|", " ZXQCHAINBREAKZXQ ").trim(), b.rect)
        }
        val translated = if (Prefs.engine(this) == 0) translateGoogle(cleaned, target) else translateMlKit(cleaned, target)
        return translated.map { it.copy(text = it.text.replace("ZXQCHAINBREAKZXQ", "⛓️‍💥").trim()) }
    }

    private fun googleTranslate(text: String, target: String): GResult {
        val tl = if (target == "zh") "zh-CN" else target
        val source = Prefs.source(this).let { if (it == "auto") "auto" else it }
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$tl&dt=t&q=" +
                URLEncoder.encode(text, "UTF-8")
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            val code = conn.responseCode
            if (code != 200) throw IOException("Serviço de tradução respondeu HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONArray(body)
            val segments = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONArray(i) ?: continue
                if (!seg.isNull(0)) sb.append(seg.getString(0))
            }
            val lang = if (root.length() > 2 && !root.isNull(2)) root.getString(2) else ""
            return GResult(sb.toString(), lang)
        } finally {
            conn.disconnect()
        }
    }

    private fun translateGoogle(blocks: List<Block>, target: String): List<Item> {
        val futures = blocks.map { b -> pool.submit(Callable { googleTranslate(b.text, target) }) }
        var firstError: Exception? = null
        val items = ArrayList<Item>()
        for ((i, f) in futures.withIndex()) {
            try {
                val r = f.get()
                val same = r.lang.equals(target, true) || r.lang.startsWith("$target-", true)
                if (!same && r.text.isNotBlank() && !r.text.equals(blocks[i].text, true)) {
                    items.add(Item(blocks[i].rect, r.text))
                }
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        val err = firstError
        if (items.isEmpty() && err != null) throw (err.cause ?: err)
        return items
    }

    private fun translateMlKit(blocks: List<Block>, target: String): List<Item> {
        val tgt = TranslateLanguage.fromLanguageTag(target)
            ?: throw IllegalStateException("Idioma de destino não suportado no modo offline.")
        val items = ArrayList<Item>()
        for (b in blocks) {
            val configuredSource = Prefs.source(this)
            val tag = if (configuredSource == "auto") {
                Tasks.await(languageIdentifier.identifyLanguage(b.text), 20, TimeUnit.SECONDS)
            } else configuredSource
            val src = TranslateLanguage.fromLanguageTag(tag) ?: continue
            if (src == tgt) continue
            val key = "$src->$tgt"
            val translator = synchronized(mlTranslators) {
                mlTranslators.getOrPut(key) {
                    Translation.getClient(
                        TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
                    )
                }
            }
            // O ML Kit verifica o armazenamento local; o modelo não é baixado novamente se já estiver presente.
            if (key !in readyModels) {
                toast("Verificando modelo offline ($src → $tgt).")
                Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()), 180, TimeUnit.SECONDS)
                readyModels.add(key)
            }
            val out = Tasks.await(translator.translate(b.text), 30, TimeUnit.SECONDS)
            if (out.isNotBlank() && !out.equals(b.text, true)) items.add(Item(b.rect, out))
        }
        return items
    }

    // ------------------------------------------------------------ resultado na tela

    private fun avgColor(bmp: Bitmap, r: Rect): Int {
        return try {
            val x = r.left.coerceIn(0, bmp.width - 1)
            val y = r.top.coerceIn(0, bmp.height - 1)
            val w = r.width().coerceIn(1, bmp.width - x)
            val h = r.height().coerceIn(1, bmp.height - y)
            Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, x, y, w, h), 1, 1, true).getPixel(0, 0)
        } catch (_: Exception) {
            Color.BLACK
        }
    }

    private fun showResult(bmp: Bitmap, items: List<Item>) {
        dismissResult()
        val root = FrameLayout(this)
        root.setOnClickListener { dismissResult() }

        for (item in items) {
            val base = Color.WHITE
            val fg = Color.BLACK
            val padX = dp(4)
            val padY = dp(2)
            val w = item.rect.width() + padX * 2
            val h = item.rect.height() + padY * 2

            val tv = TextView(this).apply {
                text = item.text
                setTextColor(fg)
                gravity = Gravity.CENTER
                setPadding(padX, padY, padX, padY)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(4).toFloat()
                }
                val minPx = dp(8)
                val maxPx = (h * 0.8f).toInt().coerceAtLeast(minPx + 1)
                setAutoSizeTextTypeUniformWithConfiguration(minPx, maxPx, 1, TypedValue.COMPLEX_UNIT_PX)
            }
            val lp = FrameLayout.LayoutParams(w, h).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = item.rect.left - padX
                topMargin = item.rect.top - padY
            }
            root.addView(tv, lp)
        }

        val params = WindowManager.LayoutParams(
            bmp.width, bmp.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(root, params)
            resultView = root
            bringBubbleToFront()
        } catch (e: Exception) {
            toast("Não consegui exibir o resultado: ${e.message}")
        }
    }

    private fun dismissResult() {
        resultView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        resultView = null
    }

    private fun toast(s: String) {
        main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show() }
    }
}
