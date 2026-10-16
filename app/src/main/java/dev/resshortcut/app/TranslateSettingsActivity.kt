package dev.resshortcut.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import rikka.shizuku.Shizuku
import java.util.Locale

class TranslateSettingsActivity : Activity() {

    private val white = Color.WHITE
    private val soft = Color.parseColor("#E0E0E0")
    private val line = Color.parseColor("#3A3A3A")

    private lateinit var overlayStatus: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var toggleButton: Button

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { updateStatus() }
    }

    // ---------------------------------------------------------------- helpers de UI

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

    private fun spinner(items: List<String>, selected: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@TranslateSettingsActivity, android.R.layout.simple_spinner_dropdown_item, items) {
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
        setPopupBackgroundDrawable(ColorDrawable(Color.parseColor("#161616")))
        setSelection(selected.coerceIn(0, items.size - 1))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onPick(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.BLACK)
        }

        root.add(label("Vyka T", 28f, white, true))
        root.add(label("Tradutor de tela", 14f, soft), 2, 16)
        overlayStatus = label("", 14f)
        root.add(overlayStatus, 0, 2)
        shizukuStatus = label("", 14f)
        root.add(shizukuStatus, 0, 10)
        root.add(button("Permitir sobreposição de tela") { requestOverlay() })
        root.add(button("Autorizar Shizuku") { requestShizuku() }, 8, 0)

        root.add(section("Traduzir para"), 22, 4)
        root.add(spinner(
            Langs.targets.map { it.first },
            Langs.targets.indexOfFirst { it.second == Prefs.target(this) }.coerceAtLeast(0)
        ) { Prefs.setTarget(this, Langs.targets[it].second) })

        root.add(section("Texto na tela (alfabeto)"), 22, 4)
        root.add(spinner(Langs.scripts, Prefs.script(this)) { Prefs.setScript(this, it) })
        root.add(label(
            "Escolha o alfabeto do texto que será lido. Inglês, espanhol, francês e similares usam o latino.",
            12f, soft
        ), 4, 0)

        root.add(section("Modo de tradução"), 22, 4)
        root.add(spinner(Langs.modes, Prefs.mode(this)) { Prefs.setMode(this, it) })
        root.add(label(
            "Global traduz a tela inteira. Região permite marcar uma área com o dedo. Os modos automáticos detectam textos sem seleção manual.",
            12f, soft
        ), 4, 0)

        root.add(section("Motor de tradução"), 22, 4)
        root.add(spinner(Langs.engines, Prefs.engine(this)) { Prefs.setEngine(this, it) })

        root.add(section("Bolha"), 22, 4)
        toggleButton = button("") { toggleBubble() }
        root.add(toggleButton)
        root.add(label(
            "Toque na bolha: traduz uma vez ou inicia/pausa um modo automático.\n" +
                "Toque duas vezes: abre os modos e os seletores de idioma de origem/destino.\n" +
                "No modo automático por região, selecione a área uma vez.\n" +
                "Arraste a bolha para mover. Segure para abrir este app.\n" +
                "Telas protegidas (bancos, alguns vídeos) saem pretas e não podem ser lidas.",
            12f, soft
        ), 14, 0)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(root)
        })

        Shizuku.addRequestPermissionResultListener(permissionListener)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- ações

    private fun requestOverlay() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun requestShizuku() {
        try {
            if (Shizuku.isPreV11()) {
                shizukuStatus.text = "Atualize o Shizuku para uma versão atual."
                return
            }
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    updateStatus()
                } else {
                    Shizuku.requestPermission(1001)
                }
            } else shizukuStatus.text = "Abra o Shizuku e inicie o serviço primeiro."
        } catch (e: Exception) {
            shizukuStatus.text = "Shizuku indisponível: ${e.message}"
        }
    }

    private fun toggleBubble() {
        if (TranslateOverlayService.running) {
            startService(Intent(this, TranslateOverlayService::class.java).setAction(TranslateOverlayService.ACTION_STOP))
        } else {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlay()
                return
            }
            startForegroundService(Intent(this, TranslateOverlayService::class.java))
        }
        toggleButton.postDelayed({ updateStatus() }, 500)
    }

    private fun updateStatus() {
        overlayStatus.text = if (Settings.canDrawOverlays(this)) "Sobreposição de tela: permitida."
        else "Sobreposição de tela: não permitida."
        shizukuStatus.text = try {
            if (!Shizuku.pingBinder()) "Shizuku não está em execução."
            else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "Shizuku conectado e autorizado."
            else "Shizuku conectado; falta autorizar."
        } catch (_: Exception) { "Abra o Shizuku e inicie o serviço." }
        toggleButton.text = if (TranslateOverlayService.running) "Parar bolha" else "Iniciar bolha"
    }
}
