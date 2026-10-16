package dev.resshortcut.app

import android.app.Activity
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Choreographer
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import kotlin.math.abs
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Launcher leve: início minimalista, favoritos e lista alfabética de apps. */
class LauncherActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: ImageView
    private lateinit var shade: View
    private lateinit var content: LinearLayout
    private lateinit var list: ListView
    private lateinit var search: EditText
    private var all: List<AppInfo> = emptyList()
    private var shown: List<AppInfo> = emptyList()
    private var profiles: Map<String, Profile> = emptyMap()
    private var showingApps = false
    private var gestureStartY = 0f
    private var statsText: TextView? = null
    private var fpsStartNanos = 0L
    private var fpsFrames = 0
    private var measuredFps = 0
    private var fpsBase = ""
    private val fpsCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isFinishing || isDestroyed || showingApps) return
            if (fpsStartNanos == 0L) fpsStartNanos = frameTimeNanos
            fpsFrames++
            val elapsed = frameTimeNanos - fpsStartNanos
            if (elapsed >= 1_000_000_000L) {
                measuredFps = (fpsFrames * 1_000_000_000L / elapsed).toInt()
                fpsStartNanos = frameTimeNanos
                fpsFrames = 0
                statsText?.text = "$fpsBase · FPS do launcher: $measuredFps"
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private val adapter = AppAdapter()
    private val prefs by lazy { getSharedPreferences("vykarl_launcher", Context.MODE_PRIVATE) }
    private val wallpaperPicker = 4812

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { loadApps() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) gestureStartY = ev.rawY
        val result = super.dispatchTouchEvent(ev)
        if (ev.action == MotionEvent.ACTION_UP) {
            val delta = ev.rawY - gestureStartY
            if (!showingApps && delta < -ui.dp(90)) showApps()
            else if (showingApps && delta > ui.dp(120) && ::list.isInitialized && list.firstVisiblePosition == 0) showHome()
        }
        return result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ui = Ui(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        wallpaper = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Papel de parede"
        }
        root.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))
        shade = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(12))
            clipToPadding = false
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        applyImmersiveMode()
        loadWallpaper()
        buildHome()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        @Suppress("DEPRECATION")
        registerReceiver(pkgReceiver, f)
        loadApps()
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        refreshProfiles()
        adapter.notifyDataSetChanged()
        loadWallpaper()
        if (!showingApps) buildHome()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        showHome()
    }

    override fun onBackPressed() {
        if (showingApps && search.text.isNotEmpty()) search.setText("") else showHome()
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(fpsCallback)
        super.onPause()
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(fpsCallback)
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun loadApps() {
        Thread {
            val apps = Apps.load(this)
            runOnUiThread {
                all = apps
                refreshProfiles()
                applyFilter()
                if (!showingApps) buildHome()
            }
        }.start()
    }

    private fun refreshProfiles() {
        val map = HashMap<String, Profile>()
        for (a in all) Profiles.get(this, a.pkg)?.let { if (it.enabled) map[a.pkg] = it }
        profiles = map
    }

    private fun applyFilter() {
        if (!::search.isInitialized) return
        val q = search.text.toString().trim().lowercase(Locale.getDefault())
        shown = if (q.isEmpty()) all else all.filter { it.label.lowercase(Locale.getDefault()).contains(q) }
        adapter.notifyDataSetChanged()
        if (showingApps && ::list.isInitialized) {
            val lp = list.layoutParams
            lp.height = ui.dp((shown.size.coerceAtLeast(1) * 64).coerceAtMost(20000))
            list.layoutParams = lp
        }
    }

    private fun loadWallpaper() {
        val raw = prefs.getString("wallpaper_uri", null)
        if (raw.isNullOrBlank()) {
            wallpaper.setImageDrawable(null)
            wallpaper.setBackgroundColor(Color.BLACK)
            return
        }
        wallpaper.setBackgroundColor(Color.BLACK)
        val uri = Uri.parse(raw)
        // Decodifica em tamanho reduzido para não manter a imagem original inteira na RAM.
        Thread {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val targetW = (resources.displayMetrics.widthPixels * 1.5f).toInt().coerceAtLeast(480)
                val targetH = (resources.displayMetrics.heightPixels * 1.5f).toInt().coerceAtLeast(800)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (bitmap == null) { wallpaper.setImageDrawable(null); return@runOnUiThread }
                    wallpaper.setImageBitmap(bitmap)
                    applyWallpaperEffects()
                }
            } catch (_: Exception) {
                runOnUiThread { wallpaper.setImageDrawable(null); wallpaper.setBackgroundColor(Color.BLACK) }
            }
        }.start()
    }

    private fun applyWallpaperEffects() {
        val blur = prefs.getInt("wallpaper_blur", 45).coerceIn(0, 100)
        val dim = prefs.getInt("wallpaper_dim", 35).coerceIn(0, 100)
        if (Build.VERSION.SDK_INT >= 31) {
            wallpaper.setRenderEffect(if (blur == 0) null else RenderEffect.createBlurEffect(blur * 0.55f, blur * 0.55f, Shader.TileMode.CLAMP))
        } else {
            wallpaper.setColorFilter(if (blur == 0) null else android.graphics.PorterDuffColorFilter(0x22000000, android.graphics.PorterDuff.Mode.SRC_ATOP))
        }
        shade.setBackgroundColor(Color.argb((dim * 2.55f).toInt().coerceIn(0,255), 0, 0, 0))
    }

    private fun showWallpaperControls() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val blurLabel = ui.label("Desfoque: ${prefs.getInt("wallpaper_blur", 45)}%", 14f, Color.WHITE)
        val blur = SeekBar(this).apply { max = 100; progress = prefs.getInt("wallpaper_blur", 45) }
        val dimLabel = ui.label("Escurecimento: ${prefs.getInt("wallpaper_dim", 35)}%", 14f, Color.WHITE)
        val dim = SeekBar(this).apply { max = 100; progress = prefs.getInt("wallpaper_dim", 35) }
        blur.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, v: Int, user: Boolean) { blurLabel.text = "Desfoque: $v%"; prefs.edit().putInt("wallpaper_blur",v).apply(); applyWallpaperEffects() }
            override fun onStartTrackingTouch(b: SeekBar?) {}; override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        dim.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, v: Int, user: Boolean) { dimLabel.text = "Escurecimento: $v%"; prefs.edit().putInt("wallpaper_dim",v).apply(); applyWallpaperEffects() }
            override fun onStartTrackingTouch(b: SeekBar?) {}; override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        panel.addView(blurLabel); panel.addView(blur); panel.addView(dimLabel); panel.addView(dim)
        AlertDialog.Builder(this, R.style.VykaDialog).setTitle("Aparência do papel de parede").setView(panel).setPositiveButton("Concluir", null).show()
    }

    private fun chooseWallpaper() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, wallpaperPicker)
    }

    @Deprecated("Android document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == wallpaperPicker && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("wallpaper_uri", uri.toString()).apply()
            showWallpaperControls()
        }
    }

    private fun buildHeader(title: String, subtitle: String? = null): LinearLayout {
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.label(title, 25f, Color.WHITE, true))
        if (subtitle != null) texts.addView(ui.label(subtitle, 12f, 0xFFBDBDBD.toInt()))
        header.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return header
    }

    private fun buildHome() {
        showingApps = false
        content.removeAllViews()
        content.addView(buildHeader("VykaR", ""))
        val spacer = View(this)
        content.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        val clock = ui.label(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 58f, Color.WHITE, true).apply { gravity = Gravity.CENTER_VERTICAL }
        content.addView(clock)
        content.addView(ui.label("Deslize de baixo para cima para ver seus aplicativos", 13f, 0xFFDDDDDD.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        content.addView(ui.button("VykaT · Tradutor de tela") {
            startActivity(Intent(this, TranslateSettingsActivity::class.java))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val stats = buildStatsBar()
        if (prefs.getBoolean("stats_top", false)) content.addView(stats, 0) else content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        fpsStartNanos = 0L; fpsFrames = 0
        Choreographer.getInstance().removeFrameCallback(fpsCallback)
        Choreographer.getInstance().postFrameCallback(fpsCallback)
    }

    private fun showApps() {
        showingApps = true
        Choreographer.getInstance().removeFrameCallback(fpsCallback)
        content.removeAllViews()
        content.addView(buildStatsBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        val groups = listOf("Outros", "Apps", "Jogos")
        groups.forEach { group ->
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(10))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0x1FFFFFFF); setStroke(ui.dp(1), 0xAA8CA6A8.toInt()); cornerRadius = ui.dp(12).toFloat() }
            }
            val assigned = assignedApps(group)
            val grid = GridLayout(this).apply { columnCount = 5; alignmentMode = GridLayout.ALIGN_BOUNDS }
            assigned.take(20).forEach { app ->
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8)) }
                cell.addView(ImageView(this@LauncherActivity).apply { setImageDrawable(app.icon); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
                cell.addView(ui.label(app.label, 9f, 0xFFEAF3F3.toInt()).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                cell.setOnClickListener { launchApp(app) }; cell.setOnLongClickListener { showMenu(app); true }
                grid.addView(cell, GridLayout.LayoutParams().apply { width = 0; height = -2; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
            }
            val add = ui.button("＋") { chooseGroupApp(group) }.apply { textSize = 26f; contentDescription = "Adicionar aplicativo a $group" }
            val addCell = FrameLayout(this).apply { addView(add, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.CENTER)) }
            grid.addView(addCell, GridLayout.LayoutParams().apply { width = 0; height = ui.dp(70); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
            panel.addView(grid)
            body.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        }
        body.addView(ui.label("Todos os aplicativos", 14f, 0xFFE5F1F1.toInt(), true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        search = ui.field("Buscar aplicativo", numeric = false).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}; override fun afterTextChanged(s: Editable?) { applyFilter() } })
            setOnEditorActionListener { _, _, _ -> shown.firstOrNull()?.let { launchApp(it) }; true }
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(0x338CA6A8); dividerHeight = ui.dp(1); selector = android.graphics.drawable.ColorDrawable(0x228CA6A8)
            isFastScrollEnabled = true; setBackgroundColor(Color.TRANSPARENT); adapter = this@LauncherActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> launchApp(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> showMenu(shown[pos]); true }
        }
        list.isNestedScrollingEnabled = false
        val listHeight = ui.dp((shown.size.coerceAtLeast(1) * 64).coerceAtMost(20000))
        body.addView(list, LinearLayout.LayoutParams(-1, listHeight))
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        applyFilter()
    }

    private fun assignedApps(group: String): List<AppInfo> {
        val stored = prefs.getString("group_$group", "") ?: ""
        return stored.split('|').filter { it.isNotBlank() }.mapNotNull { pkg -> all.firstOrNull { it.pkg == pkg } }
    }

    private fun chooseGroupApp(group: String) {
        if (all.isEmpty()) return
        val selected = assignedApps(group).map { it.pkg }.toMutableSet()
        val labels = all.map { it.label }.toTypedArray()
        val checked = BooleanArray(all.size) { all[it].pkg in selected }
        AlertDialog.Builder(this, R.style.VykaDialog).setTitle("Adicionar a $group")
            .setMultiChoiceItems(labels, checked) { _, which, yes -> if (yes) selected.add(all[which].pkg) else selected.remove(all[which].pkg) }
            .setPositiveButton("Salvar") { _, _ -> prefs.edit().putString("group_$group", selected.joinToString("|")).apply(); showApps() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showHome() { buildHome() }

    private fun buildStatsBar(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statsText = ui.label("", 10.5f, 0xFFE6E6E6.toInt()).apply {
            setSingleLine(true)
            setPadding(ui.dp(9), ui.dp(8), ui.dp(9), ui.dp(8))
        }
        fpsBase = collectStats()
        statsText?.text = "$fpsBase · FPS do launcher: —"
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(statsText, FrameLayout.LayoutParams(-2, -2))
            setOnLongClickListener {
                prefs.edit().putBoolean("stats_top", !prefs.getBoolean("stats_top", false)).apply()
                Toast.makeText(this@LauncherActivity, if (prefs.getBoolean("stats_top", false)) "Barra movida para cima" else "Barra movida para baixo", Toast.LENGTH_SHORT).show()
                if (showingApps) showApps() else buildHome()
                true
            }
        }
        scroller.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x35111111); cornerRadius = ui.dp(9).toFloat()
        }
        row.addView(scroller, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.button("✣") { confirmCleanup() }.apply {
            textSize = 27f; contentDescription = "Limpeza rápida"
            setOnClickListener { animate().rotationBy(360f).setDuration(550).withEndAction { confirmCleanup() }.start() }
            setOnLongClickListener { prefs.edit().putBoolean("cleaner_top", !prefs.getBoolean("cleaner_top", false)).apply(); Toast.makeText(this@LauncherActivity, "Botão de limpeza reposicionado. Reabra a tela para atualizar.", Toast.LENGTH_SHORT).show(); true }
        }, LinearLayout.LayoutParams(ui.dp(58), -2).apply { marginStart = ui.dp(6) })
        return row
    }

    private fun collectStats(): String {
        val battery = try {
            val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra("level", -1) ?: -1
            val scale = b?.getIntExtra("scale", 100) ?: 100
            if (level >= 0 && scale > 0) "Bateria ${level * 100 / scale}%" else "Bateria —"
        } catch (_: Exception) { "Bateria —" }
        val memory = try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo(); am.getMemoryInfo(info)
            "RAM ${(info.availMem / (1024 * 1024))} MB livres"
        } catch (_: Exception) { "RAM —" }
        val storage = try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.totalBytes / (1024L * 1024L * 1024L)
            val free = st.availableBytes / (1024L * 1024L * 1024L)
            "Armazenamento ${total - free}/${total} GB"
        } catch (_: Exception) { "Armazenamento —" }
        val cpu = "CPU ${Runtime.getRuntime().availableProcessors()} núcleos"
        val chip = if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank()) Build.SOC_MODEL else Build.HARDWARE
        val gpu = "SoC ${chip.take(22)}"
        val hz = try { "Tela ${windowManager.defaultDisplay.refreshRate.toInt()} Hz" } catch (_: Exception) { "Tela —" }
        return listOf(battery, memory, storage, cpu, gpu, hz).joinToString("  •  ")
    }

    private fun confirmCleanup() {
        AlertDialog.Builder(this, R.style.VykaDialog)
            .setTitle("Limpar aplicativos em segundo plano?")
            .setMessage("Tenta encerrar apps de terceiros fora da lista de exceções e limpar processos em cache. O Android, o teclado, o Shizuku e o launcher são protegidos. Alguns apps podem perder notificações temporariamente.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Limpar") { _, _ ->
                if (!ShizukuShell.isReady()) {
                    Toast.makeText(this, "Autorize o Shizuku nas configurações do VykaR primeiro.", Toast.LENGTH_LONG).show()
                } else Thread {
                    try {
                        val count = Cleaner.run(this, Cleaner.MODE_WHITELIST, packageName)
                        runOnUiThread { Toast.makeText(this, "Limpeza concluída: $count app(s) encerrado(s).", Toast.LENGTH_LONG).show() }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, "Falha na limpeza: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }.show()
    }

    private fun favoriteApps(): List<AppInfo> {
        val stored = prefs.getString("favorites", "") ?: ""
        val packages = stored.split('|').filter { it.isNotBlank() }
        return packages.mapNotNull { p -> all.firstOrNull { it.pkg == p } }.take(8)
    }

    private fun chooseFavorites() {
        if (all.isEmpty()) { Toast.makeText(this, "A lista de aplicativos ainda está carregando.", Toast.LENGTH_SHORT).show(); return }
        val selected = favoriteApps().map { it.pkg }.toMutableSet()
        val labels = all.map { it.label }.toTypedArray()
        val checked = BooleanArray(all.size) { all[it].pkg in selected }
        AlertDialog.Builder(this, R.style.VykaDialog)
            .setTitle("Escolher favoritos (até 8)")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = all[which].pkg
                if (isChecked) {
                    if (selected.size >= 8) {
                        Toast.makeText(this, "Você pode escolher até 8 favoritos.", Toast.LENGTH_SHORT).show()
                    } else selected.add(pkg)
                } else selected.remove(pkg)
            }
            .setPositiveButton("Salvar") { _, _ ->
                prefs.edit().putString("favorites", selected.joinToString("|")).apply()
                if (showingApps) showApps() else buildHome()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun hideKeyboard() {
        if (!::search.isInitialized) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun launchApp(app: AppInfo) {
        hideKeyboard()
        val p = profiles[app.pkg] ?: Profiles.get(this, app.pkg)?.takeIf { it.enabled }
        if (p != null) {
            if (ShizukuShell.isReady()) { startActivity(Profiles.toIntent(this, p, app.pkg)); return }
            Toast.makeText(this, "Shizuku não está pronto: abrindo sem perfil.", Toast.LENGTH_SHORT).show()
        }
        startPlain(app.pkg)
    }

    private fun startPlain(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            try { startActivity(it) } catch (e: Exception) { Toast.makeText(this, "Falha ao abrir: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showMenu(app: AppInfo) {
        val isFav = favoriteApps().any { it.pkg == app.pkg }
        val options = arrayListOf(if (isFav) "Remover dos favoritos" else "Adicionar aos favoritos", "Perfil de execução (resolução e otimizações)", "Informações do app", "Desinstalar")
        AlertDialog.Builder(this, R.style.VykaDialog).setTitle(app.label).setItems(options.toTypedArray()) { _, which ->
            when (which) {
                0 -> {
                    val set = favoriteApps().map { it.pkg }.toMutableSet()
                    if (isFav) set.remove(app.pkg) else if (set.size < 8) set.add(app.pkg) else Toast.makeText(this, "Limite de 8 favoritos.", Toast.LENGTH_SHORT).show()
                    prefs.edit().putString("favorites", set.joinToString("|")).apply()
                    if (showingApps) showApps() else buildHome()
                }
                1 -> startActivity(Intent(this, ProfileActivity::class.java).putExtra("package", app.pkg))
                2 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            }
        }.show()
    }

    private fun newRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        addView(ImageView(this@LauncherActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
        val col = LinearLayout(this@LauncherActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.label("", 16f, Color.WHITE, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        col.addView(ui.label("", 11f, 0xFFCCCCCC.toInt()).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(14) })
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = shown[position]
            val row = (convertView as? LinearLayout)?.takeIf { it.childCount == 2 } ?: newRow()
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = app.label
            val sub = col.getChildAt(1) as TextView
            val p = profiles[app.pkg]
            if (p != null) { sub.text = Profiles.summary(p); sub.visibility = View.VISIBLE }
            else sub.visibility = View.GONE
            return row
        }
    }
}
