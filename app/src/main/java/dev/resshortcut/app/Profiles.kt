package dev.resshortcut.app

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/** Perfil de execução de um app aberto pelo launcher. */
data class Profile(
    val enabled: Boolean = false,
    val match: Boolean = true, val pct: Int = 75, val cw: Int = 0, val ch: Int = 0,
    val dpiMode: Int = 0, val dpi: Int = 0,
    val clean: Int = 0, val anim: Boolean = true,
    val gameMode: Boolean = false, val noAnim: Boolean = false, val dnd: Boolean = false,
    val noSaver: Boolean = false, val hz: Int = 0
)

object Profiles {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vykarl_profiles", Context.MODE_PRIVATE)

    fun get(ctx: Context, pkg: String): Profile? {
        val s = prefs(ctx).getString(pkg, null) ?: return null
        return try {
            val j = JSONObject(s)
            Profile(
                j.optBoolean("enabled"), j.optBoolean("match", true), j.optInt("pct", 75),
                j.optInt("cw"), j.optInt("ch"), j.optInt("dpiMode"), j.optInt("dpi"),
                j.optInt("clean"), j.optBoolean("anim", true), j.optBoolean("gameMode"),
                j.optBoolean("noAnim"), j.optBoolean("dnd"), j.optBoolean("noSaver"), j.optInt("hz")
            )
        } catch (_: Exception) { null }
    }

    fun save(ctx: Context, pkg: String, p: Profile) {
        val j = JSONObject()
            .put("enabled", p.enabled).put("match", p.match).put("pct", p.pct)
            .put("cw", p.cw).put("ch", p.ch).put("dpiMode", p.dpiMode).put("dpi", p.dpi)
            .put("clean", p.clean).put("anim", p.anim).put("gameMode", p.gameMode)
            .put("noAnim", p.noAnim).put("dnd", p.dnd).put("noSaver", p.noSaver).put("hz", p.hz)
        prefs(ctx).edit().putString(pkg, j.toString()).apply()
    }

    fun remove(ctx: Context, pkg: String) {
        prefs(ctx).edit().remove(pkg).apply()
    }

    /** Mesmos extras que os atalhos usam, mais as otimizações e o "fallback" (abre o app normal se algo falhar). */
    fun toIntent(ctx: Context, p: Profile, pkg: String): Intent =
        Intent(ctx, LaunchShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("package", pkg)
            putExtra("match", p.match)
            putExtra("scale", p.pct)
            putExtra("w", p.cw)
            putExtra("h", p.ch)
            putExtra("dpiMode", p.dpiMode)
            putExtra("dpi", p.dpi)
            putExtra("clean", p.clean)
            putExtra("anim", p.anim)
            putExtra("gm", p.gameMode)
            putExtra("na", p.noAnim)
            putExtra("dnd", p.dnd)
            putExtra("ns", p.noSaver)
            putExtra("hz", p.hz)
            putExtra("fallback", true)
        }

    fun summary(p: Profile): String {
        val parts = ArrayList<String>()
        parts.add(if (p.match) "${p.pct}%" else "${p.cw}×${p.ch}")
        when (p.dpiMode) {
            Plan.DPI_AUTO -> parts.add("dpi auto")
            Plan.DPI_CUSTOM -> parts.add("dpi ${p.dpi}")
        }
        when (p.clean) {
            Cleaner.MODE_LIGHT -> parts.add("limpeza rápida")
            Cleaner.MODE_WHITELIST -> parts.add("limpeza com lista")
        }
        val boosts = listOf(p.gameMode, p.noAnim, p.dnd, p.noSaver, p.hz > 0).count { it }
        if (boosts > 0) parts.add("+$boosts otimização(ões)")
        return parts.joinToString(" · ")
    }
}
