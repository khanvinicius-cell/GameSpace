package dev.resshortcut.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*

/** Configurações avançadas de um app: resolução, DPI, limpeza e otimizações. */
class ProfileActivity : Activity() {

    private lateinit var ui: Ui
    private var pkg = ""
    private var removed = false
    private var ready = false
    private var syncing = false
    private var nativeW = 0
    private var nativeH = 0
    private var nativeDpi = 0

    private lateinit var enableSwitch: Switch
    private lateinit var matchSwitch: Switch
    private lateinit var matchGroup: LinearLayout
    private lateinit var customGroup: LinearLayout
    private lateinit var pctEdit: EditText
    private lateinit var scaleBar: SeekBar
    private lateinit var wEdit: EditText
    private lateinit var hEdit: EditText
    private lateinit var dpiSpinner: Spinner
    private lateinit var dpiEdit: EditText
    private lateinit var nativeInfo: TextView
    private lateinit var preview: TextView
    private lateinit var cleanSpinner: Spinner
    private lateinit var wlButton: Button
    private lateinit var animSwitch: Switch
    private lateinit var gameSwitch: Switch
    private lateinit var noAnimSwitch: Switch
    private lateinit var dndSwitch: Switch
    private lateinit var saverSwitch: Switch
    private lateinit var hzSpinner: Spinner
    private val hzValues = listOf(0, 60, 90, 120, 144)

    private fun EditText.onChange(block: () -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { block() }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pk = intent.getStringExtra("package")
        if (pk == null) { finish(); return }
        pkg = pk
        ui = Ui(this)

        val pm = packageManager
        val appLabel = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        val appIcon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
        val p = Profiles.get(this, pkg) ?: Profile(enabled = true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32))
            setBackgroundColor(Color.BLACK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (appIcon != null) {
            header.addView(ImageView(this).apply {
                setImageDrawable(appIcon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = ui.dp(14) })
        }
        header.addView(ui.label(appLabel, 22f, ui.white, true))
        root.addView(header)
        ui.add(root, ui.label("Perfil de execução", 14f, ui.soft), 2, 14)

        enableSwitch = ui.toggle("Usar este perfil ao abrir pelo launcher").apply { isChecked = p.enabled }
        ui.add(root, enableSwitch, 0, 6)

        // ---- Resolução
        ui.add(root, ui.section("Resolução"), 18, 4)
        nativeInfo = ui.label("Nativa: autorize o Shizuku para ler.", 13f, ui.soft)
        ui.add(root, nativeInfo, 0, 8)
        matchSwitch = ui.toggle("Manter proporção nativa").apply { isChecked = p.match }
        ui.add(root, matchSwitch, 0, 8)

        matchGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.add(matchGroup, ui.label("Escala (%), de ${Plan.MIN_PCT} a 100", 13f, ui.soft))
        pctEdit = ui.field("75")
        ui.add(matchGroup, pctEdit, 4, 6)
        scaleBar = SeekBar(this).apply { max = 100 - Plan.MIN_PCT }
        ui.add(matchGroup, scaleBar)
        root.addView(matchGroup)

        customGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.add(customGroup, ui.label("Largura × altura (px), no mesmo formato da nativa", 13f, ui.soft))
        wEdit = ui.field("largura")
        hEdit = ui.field("altura")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(wEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ui.label("×", 18f).apply { setPadding(ui.dp(10), 0, ui.dp(10), 0) })
        row.addView(hEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ui.add(customGroup, row, 4, 0)
        root.addView(customGroup)

        // ---- DPI
        ui.add(root, ui.section("DPI"), 18, 4)
        dpiSpinner = ui.spinner(
            listOf("Não alterar", "Automática (proporcional à resolução)", "Personalizada"), p.dpiMode
        ) { afterChange() }
        ui.add(root, dpiSpinner)
        dpiEdit = ui.field("ex.: 320")
        ui.add(root, dpiEdit, 6, 0)
        ui.add(root, ui.label(
            "Automática mantém o tamanho dos elementos igual ao da tela nativa. " +
                "Não alterar deixa tudo maior quando a resolução é menor.", 12f, ui.soft
        ), 6, 0)
        preview = ui.label("", 15f, ui.white, true)
        ui.add(root, preview, 14, 0)

        // ---- Ao iniciar
        ui.add(root, ui.section("Ao iniciar o app"), 20, 4)
        cleanSpinner = ui.spinner(
            listOf("Sem limpeza", "Rápida (processos em cache)", "Com lista de exceções"), p.clean
        ) { afterChange() }
        ui.add(root, cleanSpinner)
        wlButton = ui.button("") { openWhitelist() }
        ui.add(root, wlButton, 8, 0)
        ui.add(root, ui.label(
            "Rápida: o Android encerra só processos em cache. " +
                "Com lista: também fecha os apps de terceiros em execução que NÃO estão na lista de exceções " +
                "(apps do sistema nunca são tocados). Apps fechados não recebem notificações até serem abertos de novo.",
            12f, ui.soft
        ), 6, 8)
        animSwitch = ui.toggle("Animação da redução de resolução").apply { isChecked = p.anim }
        ui.add(root, animSwitch, 0, 0)

        // ---- Otimizações
        ui.add(root, ui.section("Otimizações avançadas (opcionais)"), 20, 4)
        gameSwitch = ui.toggle("Modo jogo: desempenho (Android 12+)").apply { isChecked = p.gameMode }
        ui.add(root, gameSwitch, 0, 6)
        noAnimSwitch = ui.toggle("Desativar animações do sistema").apply { isChecked = p.noAnim }
        ui.add(root, noAnimSwitch, 0, 6)
        dndSwitch = ui.toggle("Não perturbe").apply { isChecked = p.dnd }
        ui.add(root, dndSwitch, 0, 6)
        saverSwitch = ui.toggle("Desligar economia de bateria").apply { isChecked = p.noSaver }
        ui.add(root, saverSwitch, 0, 10)
        ui.add(root, ui.label("Taxa de atualização da tela", 13f, ui.soft))
        hzSpinner = ui.spinner(
            listOf("Não alterar", "60 Hz", "90 Hz", "120 Hz", "144 Hz"),
            hzValues.indexOf(p.hz).coerceAtLeast(0)
        ) { }
        ui.add(root, hzSpinner)
        ui.add(root, ui.label(
            "Tudo é revertido quando o app fecha (ou ao tocar em Restaurar). " +
                "Alguns itens dependem da versão do Android e da marca; se o sistema ignorar, nada é quebrado.",
            12f, ui.soft
        ), 6, 10)
        ui.add(root, ui.button("Compilar o app agora (acelera a abertura)") { compileNow() })
        ui.add(root, ui.label(
            "Pede ao Android para otimizar o app (pode levar de segundos a alguns minutos). Feito uma vez, dura até o app atualizar.",
            12f, ui.soft
        ), 6, 0)

        // ---- Ações
        ui.add(root, ui.button("Salvar e voltar") { finish() }, 22, 0)
        ui.add(root, ui.button("Remover este perfil") {
            removed = true
            Profiles.remove(this, pkg)
            finish()
        }, 8, 0)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(root)
        })

        // valores iniciais
        syncing = true
        pctEdit.setText(p.pct.coerceIn(Plan.MIN_PCT, 100).toString())
        scaleBar.progress = p.pct.coerceIn(Plan.MIN_PCT, 100) - Plan.MIN_PCT
        syncing = false
        if (p.cw > 0) wEdit.setText(p.cw.toString())
        if (p.ch > 0) hEdit.setText(p.ch.toString())
        if (p.dpi > 0) dpiEdit.setText(p.dpi.toString())

        scaleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) {
                    syncing = true
                    pctEdit.setText((v + Plan.MIN_PCT).toString())
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
            if (!checked && wEdit.text.isBlank() && hEdit.text.isBlank() && nativeW > 0) {
                val v = (pctEdit.text.toString().toIntOrNull() ?: 75).coerceIn(Plan.MIN_PCT, 100)
                wEdit.setText((nativeW * v / 100).toString())
                hEdit.setText((nativeH * v / 100).toString())
            }
            afterChange()
        }

        ready = true
        applyVisibility()
        updateWlButton()
        updatePreview()
        refreshNative()
    }

    override fun onPause() {
        if (!removed && pkg.isNotEmpty() && ready) Profiles.save(this, pkg, readProfile())
        super.onPause()
    }

    // ------------------------------------------------------------ lógica

    private fun afterChange() {
        if (!ready) return
        if (dpiSpinner.selectedItemPosition == Plan.DPI_CUSTOM && dpiEdit.text.isBlank() && nativeDpi > 0) {
            dpiEdit.setText(nativeDpi.toString())
        }
        applyVisibility()
        updatePreview()
    }

    private fun applyVisibility() {
        val match = matchSwitch.isChecked
        matchGroup.visibility = if (match) View.VISIBLE else View.GONE
        customGroup.visibility = if (match) View.GONE else View.VISIBLE
        dpiEdit.visibility = if (dpiSpinner.selectedItemPosition == Plan.DPI_CUSTOM) View.VISIBLE else View.GONE
        wlButton.visibility = if (cleanSpinner.selectedItemPosition == Cleaner.MODE_WHITELIST) View.VISIBLE else View.GONE
    }

    private fun updateWlButton() {
        wlButton.text = "Lista de exceções (${Cleaner.getWhitelist(this).size} apps)"
    }

    private fun openWhitelist() {
        Thread {
            val apps = Apps.load(this)
            runOnUiThread { Pickers.whitelist(this, apps) { updateWlButton() } }
        }.start()
    }

    private fun readProfile(): Profile {
        val pct = pctEdit.text.toString().toIntOrNull() ?: 75
        return Profile(
            enabled = enableSwitch.isChecked,
            match = matchSwitch.isChecked,
            pct = if (pct in Plan.MIN_PCT..100) pct else 75,
            cw = wEdit.text.toString().toIntOrNull() ?: 0,
            ch = hEdit.text.toString().toIntOrNull() ?: 0,
            dpiMode = dpiSpinner.selectedItemPosition,
            dpi = dpiEdit.text.toString().toIntOrNull() ?: 0,
            clean = cleanSpinner.selectedItemPosition,
            anim = animSwitch.isChecked,
            gameMode = gameSwitch.isChecked,
            noAnim = noAnimSwitch.isChecked,
            dnd = dndSwitch.isChecked,
            noSaver = saverSwitch.isChecked,
            hz = hzValues[hzSpinner.selectedItemPosition.coerceIn(0, hzValues.size - 1)]
        )
    }

    private fun updatePreview() {
        if (!ready) return
        if (nativeW == 0) {
            preview.text = "Prévia indisponível até o Shizuku ser autorizado."
            return
        }
        val c = readProfile()
        preview.text = try {
            val r = Plan.compute(nativeW, nativeH, nativeDpi, c.match, c.pct, c.cw, c.ch, c.dpiMode, c.dpi)
            "Resultado: ${r.w}×${r.h}" + (r.dpi?.let { " · $it dpi" } ?: " · dpi inalterado")
        } catch (e: Exception) {
            e.message ?: "Valores inválidos."
        }
    }

    private fun refreshNative() {
        if (!ShizukuShell.isReady()) return
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

    private fun compileNow() {
        if (!ShizukuShell.isReady()) {
            Toast.makeText(this, "Autorize o Shizuku primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Compilando... pode levar alguns minutos.", Toast.LENGTH_LONG).show()
        val target = pkg
        Thread {
            val msg = try {
                val r = ShizukuShell.exec("cmd package compile -m speed -f $target")
                if (r.code == 0) "Compilação concluída." else "A compilação falhou: ${r.err.ifBlank { r.out }}"
            } catch (e: Exception) { "A compilação falhou: ${e.message}" }
            runOnUiThread { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }
}
