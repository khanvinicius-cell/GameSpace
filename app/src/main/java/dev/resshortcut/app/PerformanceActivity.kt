package dev.resshortcut.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import rikka.shizuku.Shizuku
import java.util.Locale

class PerformanceActivity : Activity() {

    data class Game(val label: String, val packageName: String, val icon: Drawable)

    data class Cfg(
        val match: Boolean, val pct: Int, val cw: Int, val ch: Int,
        val dpiMode: Int, val dpi: Int, val clean: Int, val anim: Boolean
    )

    private val white = Color.WHITE
    private val soft = Color.parseColor("#E0E0E0")
    private val line = Color.parseColor("#3A3A3A")

    private lateinit var status: TextView
    private lateinit var nativeInfo: TextView
    private lateinit var preview: TextView
    private lateinit var gameSpinner: Spinner
    private lateinit var matchSwitch: Switch
    private lateinit var matchGroup: LinearLayout
    private lateinit var customGroup: LinearLayout
    private lateinit var pctEdit: EditText
    private lateinit var scaleBar: SeekBar
    private lateinit var wEdit: EditText
    private lateinit var hEdit: EditText
    private lateinit var dpiSpinner: Spinner
    private lateinit var dpiEdit: EditText
    private lateinit var cleanSwitch: Switch
    private lateinit var animSwitch: Switch

    private var games: List<Game> = emptyList()
    private var nativeW = 0
    private var nativeH = 0
    private var nativeDpi = 0
    private var syncing = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread {
            updateStatus()
            refreshNative()
        }
    }

    // ---------------------------------------------------------------- UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun LinearLayout.add(v: View, top: Int = 0, bottom: Int = 0) {
        addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(top), 0, dp(bottom)) })
    }

    private fun label(s: String, size: Float = 14f, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun section(s: String) = label(s.uppercase(Locale.getDefault()), 12f, soft, true).apply {
        letterSpacing = 0.1f
    }

    private fun box(fill: String, radius: Int = 12) = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), line)
    }

    private fun button(caption: String, onClick: () -> Unit) = Button(this).apply {
        text = caption
        isAllCaps = false
        textSize = 15f
        setTextColor(white)
        stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), box("#141414"), null)
        setOnClickListener { onClick() }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.parseColor("#808080"))
        setTextColor(white)
        textSize = 16f
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = box("#0E0E0E", 10)
    }

    private fun styledAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
        }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = styledAdapter(items)
        setPopupBackgroundDrawable(ColorDrawable(Color.parseColor("#161616")))
    }

    private fun EditText.onChange(block: () -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { block() }
        })
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Cleaner.prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.BLACK)
        }

        root.add(label("Vyka R-L", 28f, white, true))
        root.add(label("Atalhos de jogos com resolução reduzida", 14f, soft), 2, 16)
        status = label("", 14f)
        root.add(status, 0, 8)
        root.add(button("Verificar / autorizar Shizuku") { requestShizuku() })

        root.add(section("Jogo"), 22, 4)
        gameSpinner = spinner(emptyList())
        root.add(gameSpinner)

        root.add(section("Resolução"), 22, 4)
        nativeInfo = label("Resolução nativa: autorize o Shizuku para ler.", 13f, soft)
        root.add(nativeInfo, 0, 8)
        matchSwitch = Switch(this).apply {
            text = "Manter proporção nativa"
            textSize = 15f
            setTextColor(white)
        }
        root.add(matchSwitch, 0, 8)

        matchGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        matchGroup.add(label("Escala (%), de ${Plan.MIN_PCT} a 100", 13f, soft))
        pctEdit = field("50")
        matchGroup.add(pctEdit, 4, 6)
        scaleBar = SeekBar(this).apply { max = 100 - Plan.MIN_PCT }
        matchGroup.add(scaleBar)
        root.add(matchGroup)

        customGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        customGroup.add(label("Largura × altura (px), no mesmo formato da nativa", 13f, soft))
        wEdit = field("largura")
        hEdit = field("altura")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(wEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("×", 18f).apply { setPadding(dp(10), 0, dp(10), 0) })
        row.addView(hEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        customGroup.add(row, 4, 0)
        root.add(customGroup)

        root.add(section("DPI"), 22, 4)
        dpiSpinner = spinner(listOf("Não alterar", "Automática (proporcional à resolução)", "Personalizada"))
        root.add(dpiSpinner)
        dpiEdit = field("ex.: 320")
        root.add(dpiEdit, 6, 0)
        root.add(label(
            "Automática mantém o tamanho dos elementos igual ao da tela nativa. " +
                "Não alterar deixa tudo maior quando a resolução é menor.",
            12f, soft
        ), 6, 0)

        preview = label("", 15f, white, true)
        root.add(preview, 14, 0)

        root.add(section("Ao iniciar o jogo"), 22, 4)
        cleanSwitch = Switch(this).apply {
            text = "Limpeza rápida de cache"
            textSize = 15f
            setTextColor(white)
        }
        root.add(cleanSwitch, 0, 2)
        root.add(label(
            "Pede ao Android para encerrar só processos em cache. Não fecha nada que esteja em uso.",
            12f, soft
        ), 0, 10)
        animSwitch = Switch(this).apply {
            text = "Animação da redução de resolução"
            textSize = 15f
            setTextColor(white)
        }
        root.add(animSwitch, 0, 2)
        root.add(label("Mostra, por um instante, quanto a resolução vai ser reduzida.", 12f, soft), 0, 0)

        root.add(section("Launcher"), 22, 4)
        root.add(button("Definir o Vyka R-L como launcher padrão") { openHomeSettings() })
        root.add(button("Lista de exceções da limpeza") { openWhitelist() }, 8, 0)
        root.add(label(
            "No launcher, segure o dedo sobre um app para configurar resolução, DPI, limpeza e otimizações só para ele.",
            12f, soft
        ), 6, 0)

        root.add(section("Ações"), 22, 4)
        root.add(button("Abrir agora (com estas opções)") { openNow() })
        root.add(button("Criar atalho na tela inicial") { createShortcut() }, 8, 0)
        root.add(button("Criar atalho para restaurar resolução") { pinResetShortcut() }, 8, 0)
        root.add(label(
            "Importante: a resolução e o DPI mudam para o Android inteiro e afetam a interface do sistema até serem restaurados. " +
                "Use o atalho Restaurar depois de jogar, ou feche o jogo para a restauração automática.",
            12f, soft
        ), 20, 0)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(root)
        })

        // valores salvos
        syncing = true
        val pct = prefs.getInt("pct", 50).coerceIn(Plan.MIN_PCT, 100)
        pctEdit.setText(pct.toString())
        scaleBar.progress = pct - Plan.MIN_PCT
        syncing = false
        wEdit.setText(prefs.getString("cw", "") ?: "")
        hEdit.setText(prefs.getString("ch", "") ?: "")
        dpiEdit.setText(prefs.getString("dpi", "") ?: "")
        matchSwitch.isChecked = prefs.getBoolean("match", true)
        dpiSpinner.setSelection(prefs.getInt("dpi_mode", 0).coerceIn(0, 2))
        cleanSwitch.isChecked = if (prefs.contains("clean_on")) prefs.getBoolean("clean_on", false)
        else prefs.getInt("clean_mode", 0) > 0
        animSwitch.isChecked = prefs.getBoolean("anim", true)
        applyVisibility()

        attachListeners()
        loadGames()
        updateStatus()
        updatePreview()
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    private fun attachListeners() {
        scaleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    syncing = true
                    pctEdit.setText((p + Plan.MIN_PCT).toString())
                    syncing = false
                    updatePreview()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        pctEdit.onChange {
            if (!syncing) {
                val v = pctEdit.text.toString().toIntOrNull()
                if (v != null && v in Plan.MIN_PCT..100) scaleBar.progress = v - Plan.MIN_PCT
            }
            updatePreview()
        }
        wEdit.onChange { updatePreview() }
        hEdit.onChange { updatePreview() }
        dpiEdit.onChange { updatePreview() }

        matchSwitch.setOnCheckedChangeListener { _, checked ->
            applyVisibility()
            if (!checked && wEdit.text.isBlank() && hEdit.text.isBlank() && nativeW > 0) {
                val p = (pctEdit.text.toString().toIntOrNull() ?: 50).coerceIn(Plan.MIN_PCT, 100)
                wEdit.setText((nativeW * p / 100).toString())
                hEdit.setText((nativeH * p / 100).toString())
            }
            updatePreview()
        }
        dpiSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyVisibility()
                if (pos == Plan.DPI_CUSTOM && dpiEdit.text.isBlank() && nativeDpi > 0) {
                    dpiEdit.setText(nativeDpi.toString())
                }
                updatePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        cleanSwitch.setOnCheckedChangeListener { _, checked ->
            Cleaner.prefs(this).edit().putBoolean("clean_on", checked).apply()
        }
        animSwitch.setOnCheckedChangeListener { _, checked ->
            Cleaner.prefs(this).edit().putBoolean("anim", checked).apply()
        }
    }

    private fun applyVisibility() {
        val match = matchSwitch.isChecked
        matchGroup.visibility = if (match) View.VISIBLE else View.GONE
        customGroup.visibility = if (match) View.GONE else View.VISIBLE
        dpiEdit.visibility = if (dpiSpinner.selectedItemPosition == Plan.DPI_CUSTOM) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshNative()
    }

    override fun onPause() {
        saveUi()
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun saveUi() {
        val c = readCfg()
        val e = Cleaner.prefs(this).edit()
            .putBoolean("match", c.match)
            .putInt("dpi_mode", c.dpiMode)
            .putString("cw", wEdit.text.toString())
            .putString("ch", hEdit.text.toString())
            .putString("dpi", dpiEdit.text.toString())
            .putBoolean("clean_on", cleanSwitch.isChecked)
            .putBoolean("anim", animSwitch.isChecked)
        if (c.pct in Plan.MIN_PCT..100) e.putInt("pct", c.pct)
        games.getOrNull(gameSpinner.selectedItemPosition)?.let { e.putString("game", it.packageName) }
        e.apply()
    }

    // ---------------------------------------------------------------- configuração e prévia

    private fun readCfg() = Cfg(
        match = matchSwitch.isChecked,
        pct = pctEdit.text.toString().toIntOrNull() ?: 0,
        cw = wEdit.text.toString().toIntOrNull() ?: 0,
        ch = hEdit.text.toString().toIntOrNull() ?: 0,
        dpiMode = dpiSpinner.selectedItemPosition,
        dpi = dpiEdit.text.toString().toIntOrNull() ?: 0,
        clean = if (cleanSwitch.isChecked) Cleaner.MODE_LIGHT else Cleaner.MODE_NONE,
        anim = animSwitch.isChecked
    )

    private fun plan(c: Cfg) = Plan.compute(
        nativeW, nativeH, nativeDpi, c.match, c.pct, c.cw, c.ch, c.dpiMode, c.dpi
    )

    private fun updatePreview() {
        if (nativeW == 0) {
            preview.text = "Prévia indisponível até o Shizuku ser autorizado."
            return
        }
        preview.text = try {
            val r = plan(readCfg())
            "Resultado: ${r.w}×${r.h}" + (r.dpi?.let { " · $it dpi" } ?: " · dpi inalterado")
        } catch (e: Exception) {
            e.message ?: "Valores inválidos."
        }
    }

    private fun refreshNative() {
        if (!hasShizukuPermission()) return
        Thread {
            try {
                val s = ShizukuShell.run("wm size")
                val d = ShizukuShell.run("wm density")
                val m = Regex("Physical size: (\\d+)x(\\d+)").find(s)
                val dm = Regex("Physical density: (\\d+)").find(d)
                if (m != null) {
                    nativeW = m.groupValues[1].toInt()
                    nativeH = m.groupValues[2].toInt()
                }
                if (dm != null) nativeDpi = dm.groupValues[1].toInt()
                runOnUiThread {
                    if (nativeW > 0) {
                        nativeInfo.text = "Nativa: ${nativeW}×${nativeH}" +
                            (if (nativeDpi > 0) " · $nativeDpi dpi" else "")
                    }
                    updatePreview()
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ---------------------------------------------------------------- Shizuku

    private fun requestShizuku() {
        try {
            if (Shizuku.isPreV11()) {
                status.text = "Atualize o Shizuku para uma versão atual."
                return
            }
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    status.text = "Shizuku autorizado."
                    refreshNative()
                } else {
                    Shizuku.requestPermission(1001)
                }
            } else status.text = "Abra o Shizuku e inicie o serviço primeiro."
        } catch (e: Exception) {
            status.text = "Shizuku indisponível: ${e.message}"
        }
    }

    private fun updateStatus() {
        status.text = try {
            if (!Shizuku.pingBinder()) "Shizuku não está em execução."
            else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "Shizuku conectado e autorizado."
            else "Shizuku conectado; falta autorizar."
        } catch (_: Exception) { "Abra o Shizuku e inicie o serviço." }
    }

    private fun hasShizukuPermission(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- jogos

    private fun loadGames() {
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(intent, 0)
                .mapNotNull {
                    val pkg = it.activityInfo.packageName
                    if (pkg == packageName) null else Game(it.loadLabel(pm).toString(), pkg, it.loadIcon(pm))
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase(Locale.getDefault()) }
            runOnUiThread {
                games = list
                gameSpinner.adapter = GameAdapter(list)
                val last = Cleaner.prefs(this).getString("game", null)
                val idx = list.indexOfFirst { it.packageName == last }
                if (idx >= 0) gameSpinner.setSelection(idx)
            }
        }.start()
    }

    /** Linha com ícone + nome, usada no seletor de jogos. */
    private inner class GameAdapter(private val items: List<Game>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            gameRow(items[position], convertView, 8)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            gameRow(items[position], convertView, 12)
    }

    private fun appRowContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(this@PerformanceActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        addView(
            label("", 15f).apply {
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        )
    }

    private fun gameRow(game: Game, convert: View?, vPad: Int): View {
        val row = (convert as? LinearLayout)?.takeIf { it.childCount == 2 } ?: appRowContainer()
        (row.getChildAt(0) as ImageView).setImageDrawable(game.icon)
        (row.getChildAt(1) as TextView).text = game.label
        row.setPadding(dp(8), dp(vPad), dp(8), dp(vPad))
        return row
    }

    /** Converte o ícone do app em Icon para o atalho (adaptativo quando possível). */
    private fun shortcutIcon(game: Game): Icon = try {
        val src = game.icon
        val d = (src.constantState?.newDrawable(resources) ?: src).mutate()
        if (d is AdaptiveIconDrawable) {
            val n = 256
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.background?.let { it.setBounds(0, 0, n, n); it.draw(c) }
            d.foreground?.let { it.setBounds(0, 0, n, n); it.draw(c) }
            Icon.createWithAdaptiveBitmap(bmp)
        } else {
            val n = 192
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, n, n)
            d.draw(c)
            Icon.createWithBitmap(bmp)
        }
    } catch (e: Exception) {
        Icon.createWithResource(this, android.R.drawable.ic_media_play)
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            catch (e: Exception) { toast("Abra Configurações → Apps → Apps padrão → App de início.") }
        }
    }

    private fun openWhitelist() {
        Thread {
            val apps = Apps.load(this)
            runOnUiThread { Pickers.whitelist(this, apps) { toast("Lista de exceções salva.") } }
        }.start()
    }

    // ---------------------------------------------------------------- atalhos

    private fun validated(): Pair<Game, Cfg>? {
        val game = games.getOrNull(gameSpinner.selectedItemPosition)
        if (game == null) { toast("Não encontrei jogos/aplicativos iniciáveis."); return null }
        if (!hasShizukuPermission()) { toast("Autorize o Shizuku primeiro."); return null }
        if (nativeW == 0) { toast("Ainda lendo a resolução nativa. Tente de novo em instantes."); return null }
        val cfg = readCfg()
        try {
            plan(cfg)
        } catch (e: Exception) {
            toast(e.message ?: "Valores inválidos.")
            return null
        }
        return game to cfg
    }

    private fun launchIntent(game: Game, c: Cfg) =
        Intent(this, LaunchShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("package", game.packageName)
            putExtra("match", c.match)
            putExtra("scale", c.pct)
            putExtra("w", c.cw)
            putExtra("h", c.ch)
            putExtra("dpiMode", c.dpiMode)
            putExtra("dpi", c.dpi)
            putExtra("clean", c.clean)
            putExtra("anim", c.anim)
        }

    private fun openNow() {
        val (game, cfg) = validated() ?: return
        startActivity(launchIntent(game, cfg))
    }

    private fun createShortcut() {
        val (game, cfg) = validated() ?: return
        val r = plan(cfg)
        val sizeTag = if (cfg.match) "p${cfg.pct}" else "${r.w}x${r.h}"
        val dpiTag = when (cfg.dpiMode) {
            Plan.DPI_AUTO -> "da"
            Plan.DPI_CUSTOM -> "d${cfg.dpi}"
            else -> "d0"
        }
        val id = "g_${game.packageName.replace('.', '_')}_${sizeTag}_${dpiTag}_c${cfg.clean}"
        val sizeLabel = if (cfg.match) "${cfg.pct}%" else "${r.w}x${r.h}"
        val dpiLabel = when (cfg.dpiMode) {
            Plan.DPI_AUTO -> ", dpi automático (${r.dpi})"
            Plan.DPI_CUSTOM -> ", dpi ${r.dpi}"
            else -> ""
        }
        val cleanLabel = when (cfg.clean) {
            Cleaner.MODE_LIGHT -> ", limpeza rápida"
            else -> ""
        }
        val info = ShortcutInfo.Builder(this, id)
            .setShortLabel("${game.label} $sizeLabel")
            .setLongLabel("${game.label} — $sizeLabel$dpiLabel$cleanLabel")
            .setIcon(shortcutIcon(game))
            .setIntent(launchIntent(game, cfg))
            .build()
        pin(info)
    }

    private fun pinResetShortcut() {
        val intent = Intent(this, LaunchShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("reset", true)
        }
        val info = ShortcutInfo.Builder(this, "restore_resolution")
            .setShortLabel("Restaurar resolução")
            .setLongLabel("Restaurar resolução padrão")
            .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_revert))
            .setIntent(intent)
            .build()
        pin(info)
    }

    private fun pin(info: ShortcutInfo) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm.isRequestPinShortcutSupported) {
            sm.requestPinShortcut(info, null)
            toast("Confirme a criação do atalho na tela inicial.")
        } else toast("Este launcher não permite fixar atalhos.")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
