package dev.resshortcut.app

import android.content.Context
import org.json.JSONObject

/** Otimizações opcionais via ADB. Tudo é desfeito quando o jogo fecha (ou no atalho Restaurar). */
object Boost {
    data class Opts(
        val gameMode: Boolean = false,
        val noAnim: Boolean = false,
        val dnd: Boolean = false,
        val noSaver: Boolean = false,
        val hz: Int = 0
    ) {
        fun any() = gameMode || noAnim || dnd || noSaver || hz > 0
    }

    private const val KEY = "boost_orig"
    private val ANIM_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    /** Aplica as otimizações. Devolve quantas foram aplicadas. */
    fun apply(ctx: Context, pkg: String, o: Opts): Int {
        val prefs = Cleaner.prefs(ctx)
        val saved = try { JSONObject(prefs.getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        var n = 0

        fun remember(key: String, readCmd: String) {
            if (!saved.has(key)) saved.put(key, ShizukuShell.exec(readCmd).out.trim())
        }

        if (o.noAnim) {
            for (k in ANIM_KEYS) {
                remember("g:$k", "settings get global $k")
                ShizukuShell.exec("settings put global $k 0")
            }
            n++
        }
        if (o.dnd) {
            remember("zen", "settings get global zen_mode")
            ShizukuShell.exec("cmd notification set_dnd priority")
            n++
        }
        if (o.noSaver) {
            remember("g:low_power", "settings get global low_power")
            ShizukuShell.exec("settings put global low_power 0")
            n++
        }
        if (o.hz > 0) {
            remember("s:min_refresh_rate", "settings get system min_refresh_rate")
            remember("s:peak_refresh_rate", "settings get system peak_refresh_rate")
            ShizukuShell.exec("settings put system min_refresh_rate ${o.hz}.0")
            ShizukuShell.exec("settings put system peak_refresh_rate ${o.hz}.0")
            n++
        }
        if (o.gameMode && ShizukuShell.isValidPackage(pkg)) {
            saved.put("gm", pkg)
            ShizukuShell.exec("cmd game mode performance $pkg")
            n++
        }
        prefs.edit().putString(KEY, saved.toString()).apply()
        return n
    }

    /** Desfaz tudo o que foi aplicado. */
    fun restore(ctx: Context) {
        val prefs = Cleaner.prefs(ctx)
        val raw = prefs.getString(KEY, null) ?: return
        val saved = JSONObject(raw)
        for (k in saved.keys()) {
            val v = saved.optString(k, "").trim()
            val missing = v.isEmpty() || v == "null"
            when {
                k.startsWith("g:") || k.startsWith("s:") -> {
                    val ns = if (k.startsWith("g:")) "global" else "system"
                    val name = k.substring(2)
                    ShizukuShell.exec(if (missing) "settings delete $ns $name" else "settings put $ns $name $v")
                }
                k == "zen" -> {
                    val mode = when (v) { "1" -> "priority"; "2" -> "none"; "3" -> "alarms"; else -> "off" }
                    ShizukuShell.exec("cmd notification set_dnd $mode")
                }
                k == "gm" -> {
                    if (ShizukuShell.isValidPackage(v)) ShizukuShell.exec("cmd game mode standard $v")
                }
            }
        }
        prefs.edit().remove(KEY).apply()
    }
}
