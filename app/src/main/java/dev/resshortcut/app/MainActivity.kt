package dev.resshortcut.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import rikka.shizuku.Shizuku

/** Novo painel Game Space. As telas técnicas existentes continuam acessíveis em Desempenho. */
class MainActivity : Activity() {
    private val bg = Color.rgb(0, 0, 0)
    private val card = Color.rgb(31, 31, 31)
    private val muted = Color.rgb(170, 170, 170)
    private val accent = Color.rgb(165, 255, 91)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 20, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun actionCard(icon: String, title: String, subtitle: String, click: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(17), dp(14), dp(16)); background = shape(card, 22)
            isClickable = true; isFocusable = true
            addView(text(icon, 25f, accent, true), LinearLayout.LayoutParams(-1, dp(38)))
            addView(text(title, 19f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            addView(text(subtitle, 14f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            setOnClickListener { click() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(24)) }
        scroll.addView(content); outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = TextView(this).apply { text = "⌘"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(accent); background = shape(card, 16); }
        header.addView(mark, LinearLayout.LayoutParams(dp(54), dp(54)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, 0, 0) }
        titles.addView(text("VykaR", 25f, Color.WHITE, true))
        titles.addView(text("GAME SPACE  ·  PAINEL DE CONTROLE", 10f, muted, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = TextView(this).apply { text = "☷"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setOnClickListener { openSettings() } }
        header.addView(settings, LinearLayout.LayoutParams(dp(40), dp(48)))
        content.addView(header)

        val status = text("VERIFICANDO SHIZUKU…", 12f, muted, true)
        status.setPadding(dp(2), dp(18), 0, dp(12)); content.addView(status)
        content.addView(View(this).apply { setBackgroundColor(0xFF292929.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        content.addView(text("Seu espaço de jogo", 24f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(25) })
        content.addView(text("Acesso rápido às ferramentas, sem alterar o launcher do Android.", 14f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(20) })

        val grid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = false }
        val cards = listOf(
            actionCard("⌑", "Jogos", "Biblioteca, perfis e inicialização.") { startActivity(Intent(this@MainActivity, LauncherActivity::class.java)) },
            actionCard("ϟ", "Desempenho", "Resolução, DPI, limpeza e modo gamer.") { startActivity(Intent(this@MainActivity, PerformanceActivity::class.java)) },
            actionCard("文A", "VykaT", "Tradutor, modos e botão flutuante.") { openTranslate() },
            actionCard("☷", "Configurações", "Comportamento e preferências gerais.") { openSettings() }
        )
        cards.forEachIndexed { i, v ->
            val lp = GridLayout.LayoutParams().apply {
                width = 0; height = dp(170); columnSpec = GridLayout.spec(i % 2, 1f); rowSpec = GridLayout.spec(i / 2)
                setMargins(if (i % 2 == 0) 0 else dp(6), if (i < 2) 0 else dp(12), if (i % 2 == 0) dp(6) else 0, 0)
            }
            grid.addView(v, lp)
        }
        content.addView(grid)
        content.addView(text("AÇÕES RÁPIDAS", 11f, muted, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26); bottomMargin = dp(10) })
        val restore = TextView(this).apply {
            text = "↺    Restaurar resolução e DPI"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0); background = shape(Color.rgb(18,18,18), 14, 0xFF333333.toInt())
            setOnClickListener { restoreDisplay() }
        }
        content.addView(restore, LinearLayout.LayoutParams(-1, dp(54)))
        val translate = TextView(this).apply {
            text = if (TranslateOverlayService.running) "■    Parar botão flutuante do VykaT" else "文A    Iniciar botão flutuante do VykaT"
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0); background = shape(Color.rgb(18,18,18), 14, 0xFF333333.toInt())
            setOnClickListener { toggleTranslate(); text = if (TranslateOverlayService.running) "■    Parar botão flutuante do VykaT" else "文A    Iniciar botão flutuante do VykaT" }
        }
        content.addView(translate, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(9) })
        content.addView(text("VykaR Game Space", 12f, 0xFF666666.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.BLACK) }
        listOf("⌑\nInício", "▦\nJogos", "ϟ\nDesempenho", "文A\nVykaT").forEachIndexed { i, label ->
            val item = TextView(this).apply { text = label; gravity = Gravity.CENTER; textSize = 11f; setTextColor(if (i == 0) accent else muted); setOnClickListener {
                when (i) { 1 -> startActivity(Intent(this@MainActivity, LauncherActivity::class.java)); 2 -> startActivity(Intent(this@MainActivity, PerformanceActivity::class.java)); 3 -> openTranslate() }
            } }
            nav.addView(item, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        outer.addView(nav)
        setContentView(outer)
        status.text = shizukuStatus()
    }
    override fun onResume() { super.onResume() }
    private fun shizukuStatus() = try {
        when { !Shizuku.pingBinder() -> "●  SHIZUKU DESCONECTADO"; Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED -> "●  SHIZUKU AUTORIZADO"; else -> "●  SHIZUKU AGUARDANDO AUTORIZAÇÃO" }
    } catch (_: Exception) { "●  SHIZUKU INDISPONÍVEL" }
    private fun openTranslate() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this).setTitle("Permitir botão flutuante").setMessage("O VykaT precisa da permissão de sobreposição para exibir o botão sobre os jogos.")
                .setNegativeButton("Agora não", null).setPositiveButton("Abrir permissão") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }.show()
        } else startActivity(Intent(this, TranslateSettingsActivity::class.java))
    }
    private fun toggleTranslate() {
        if (!Settings.canDrawOverlays(this)) { openTranslate(); return }
        if (TranslateOverlayService.running) stopService(Intent(this, TranslateOverlayService::class.java))
        else startForegroundService(Intent(this, TranslateOverlayService::class.java))
    }
    private fun restoreDisplay() {
        AlertDialog.Builder(this).setTitle("Restaurar tela").setMessage("Restaurar resolução e densidade nativas exige Shizuku autorizado. Deseja abrir os controles de desempenho?")
            .setNegativeButton("Cancelar", null).setPositiveButton("Abrir") { _, _ -> startActivity(Intent(this, PerformanceActivity::class.java)) }.show()
    }
    private fun openSettings() {
        val options = arrayOf("Configurações de desempenho", "Configuração do VykaT", "Permissão de sobreposição", "Autorizar Shizuku")
        AlertDialog.Builder(this).setTitle("Configurações").setItems(options) { _, which -> when(which) {
            0 -> startActivity(Intent(this, PerformanceActivity::class.java))
            1 -> openTranslate()
            2 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            3 -> startActivity(Intent(this, PerformanceActivity::class.java))
        } }.show()
    }
}
