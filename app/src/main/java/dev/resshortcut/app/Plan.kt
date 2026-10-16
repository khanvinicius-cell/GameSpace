package dev.resshortcut.app

import android.content.Context
import kotlin.math.roundToInt

/** Calcula a resolução e o DPI de destino. */
object Plan {
    const val MIN_PCT = 20
    const val MIN_SIDE = 200
    const val MIN_DPI = 72
    const val MAX_DPI = 960

    const val DPI_KEEP = 0
    const val DPI_AUTO = 1
    const val DPI_CUSTOM = 2

    data class Result(val w: Int, val h: Int, val dpi: Int?)

    /**
     * match = true  -> usa [pct] sobre a resolução nativa (mantém a proporção).
     * match = false -> usa [cw] x [ch] livremente.
     * DPI automático = DPI nativo x (resolução nova / nativa), o que mantém o tamanho
     * dos elementos igual ao da tela nativa.
     */
    fun compute(
        physW: Int, physH: Int, physDpi: Int,
        match: Boolean, pct: Int, cw: Int, ch: Int,
        dpiMode: Int, customDpi: Int
    ): Result {
        val w: Int
        val h: Int
        if (match) {
            require(pct in MIN_PCT..100) { "A escala deve ficar entre $MIN_PCT e 100%." }
            w = physW * pct / 100
            h = physH * pct / 100
        } else {
            require(cw in MIN_SIDE..physW) { "A largura deve ficar entre $MIN_SIDE e $physW px." }
            require(ch in MIN_SIDE..physH) { "A altura deve ficar entre $MIN_SIDE e $physH px." }
            w = cw
            h = ch
        }
        val ew = w - w % 2
        val eh = h - h % 2
        require(minOf(ew, eh) >= 120) { "A resolução resultante ficou pequena demais." }

        val dpi: Int? = when (dpiMode) {
            DPI_AUTO -> {
                check(physDpi > 0) { "Não consegui ler o DPI nativo." }
                val ratio = minOf(ew.toDouble() / physW, eh.toDouble() / physH)
                (physDpi * ratio).roundToInt().coerceAtLeast(MIN_DPI)
            }
            DPI_CUSTOM -> {
                require(customDpi in MIN_DPI..MAX_DPI) { "O DPI deve ficar entre $MIN_DPI e $MAX_DPI." }
                customDpi
            }
            else -> null
        }
        return Result(ew, eh, dpi)
    }
}

/** Aplica e desfaz as alterações de tela. */
object DisplayControl {
    private const val KEY_CHANGED = "dpi_changed"
    private const val KEY_ORIG = "dpi_orig"

    fun apply(ctx: Context, plan: Plan.Result) {
        val prefs = Cleaner.prefs(ctx)
        if (plan.dpi != null && !prefs.getBoolean(KEY_CHANGED, false)) {
            // Guarda o DPI que estava definido antes (se havia um ajuste manual do usuário).
            val d = ShizukuShell.run("wm density")
            val orig = Regex("Override density: (\\d+)").find(d)?.groupValues?.get(1) ?: ""
            prefs.edit().putBoolean(KEY_CHANGED, true).putString(KEY_ORIG, orig).apply()
        }
        val cmd = if (plan.dpi != null) "wm size ${plan.w}x${plan.h} && wm density ${plan.dpi}"
        else "wm size ${plan.w}x${plan.h}"
        ShizukuShell.run(cmd)
    }

    fun restore(ctx: Context) {
        val prefs = Cleaner.prefs(ctx)
        ShizukuShell.run("wm size reset")
        if (prefs.getBoolean(KEY_CHANGED, false)) {
            val orig = prefs.getString(KEY_ORIG, "") ?: ""
            ShizukuShell.run(if (orig.isNotEmpty()) "wm density $orig" else "wm density reset")
            prefs.edit().putBoolean(KEY_CHANGED, false).apply()
        }
        try { Boost.restore(ctx) } catch (_: Exception) {}
    }
}
