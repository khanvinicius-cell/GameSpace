package dev.resshortcut.app

import android.content.Context
import android.content.SharedPreferences

object Langs {
    /** Nome para exibir -> código do idioma. */
    val languages = listOf(
        "Detectar automaticamente" to "auto", "Português" to "pt", "Inglês" to "en",
        "Japonês" to "ja", "Espanhol" to "es", "Francês" to "fr", "Alemão" to "de",
        "Italiano" to "it", "Coreano" to "ko", "Chinês" to "zh", "Russo" to "ru",
        "Turco" to "tr", "Árabe" to "ar", "Indonésio" to "id", "Vietnamita" to "vi"
    )
    val targets = languages.filter { it.second != "auto" }
    val scripts = listOf("Latino (inglês, espanhol, francês...)", "Japonês", "Chinês", "Coreano")
    val engines = listOf("Google (online, melhor qualidade)", "ML Kit (offline, baixa modelos no 1º uso)")
    val modes = listOf("Tradução global", "Tradução de região (seleção manual)", "Automática de região", "Automática global")
}

object Prefs {
    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("vykat", Context.MODE_PRIVATE)

    fun source(ctx: Context): String = p(ctx).getString("source", "auto") ?: "auto"
    fun setSource(ctx: Context, v: String) = p(ctx).edit().putString("source", v).apply()

    fun target(ctx: Context): String = p(ctx).getString("target", "pt") ?: "pt"
    fun setTarget(ctx: Context, v: String) = p(ctx).edit().putString("target", v).apply()

    fun script(ctx: Context): Int = p(ctx).getInt("script", 0)
    fun setScript(ctx: Context, v: Int) = p(ctx).edit().putInt("script", v).apply()

    fun mode(ctx: Context): Int = p(ctx).getInt("mode", 0)
    fun setMode(ctx: Context, v: Int) = p(ctx).edit().putInt("mode", v).apply()

    fun engine(ctx: Context): Int = p(ctx).getInt("engine", 0)
    fun setEngine(ctx: Context, v: Int) = p(ctx).edit().putInt("engine", v).apply()

    fun bubbleX(ctx: Context, def: Int): Int = p(ctx).getInt("bx", def)
    fun bubbleY(ctx: Context, def: Int): Int = p(ctx).getInt("by", def)
    fun setBubble(ctx: Context, x: Int, y: Int) = p(ctx).edit().putInt("bx", x).putInt("by", y).apply()
}
