package dev.resshortcut.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings

/** Limpeza antes de abrir o jogo. */
object Cleaner {
    const val MODE_NONE = 0
    const val MODE_LIGHT = 1       // só processos em cache
    const val MODE_WHITELIST = 2   // cache + apps de terceiros em execução fora da lista de exceções

    private const val PREFS = "vyka_prefs"
    private const val KEY_WHITELIST = "whitelist"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getWhitelist(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()

    fun setWhitelist(ctx: Context, list: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_WHITELIST, HashSet(list)).apply()
    }

    /** Pacotes que nunca são encerrados (nem aparecem na lista de exceções). */
    fun protectedPackages(ctx: Context, game: String?): Set<String> {
        val result = mutableSetOf(ctx.packageName, SHIZUKU_PACKAGE, "com.android.systemui", "android")
        game?.let { result.add(it) }
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { result.add(it) }
        } catch (_: Exception) {}
        try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        } catch (_: Exception) {}
        return result
    }

    /** Executa a limpeza. Devolve quantos apps foram encerrados (modo com lista). */
    fun run(ctx: Context, mode: Int, game: String): Int {
        if (mode == MODE_NONE) return 0
        var count = 0
        if (mode == MODE_WHITELIST) {
            // Só apps de terceiros (sistema nunca é tocado) que estão em execução agora.
            val installed = ShizukuShell.exec("pm list packages -3").out.lines()
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() && ShizukuShell.isValidPackage(it) }
                .toSet()
            val running = ShizukuShell.exec("ps -A -o NAME").out.lines()
                .map { it.trim().substringBefore(':') }
                .toSet()
            val skip = protectedPackages(ctx, game) + getWhitelist(ctx)
            val targets = installed.filter { it in running && it !in skip }
            count = targets.size
            // Em grupos pequenos e em paralelo, para ser rápido sem estourar a memória.
            for (chunk in targets.chunked(4)) {
                ShizukuShell.exec(chunk.joinToString(" & ") { "am force-stop $it" } + " & wait")
            }
        }
        // Pede ao Android que encerre só os processos em cache/vazios.
        ShizukuShell.exec("am kill-all")
        return count
    }
}
