package dev.resshortcut.app

import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException

object ShizukuShell {
    data class Result(val code: Int, val out: String, val err: String)

    fun isReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0
    } catch (_: Exception) { false }

    /** Inicia um comando via Shizuku sem esperar terminar. */
    fun start(command: String): Process = try {
        // Shizuku.newProcess é privado a partir da API 13; acessamos por reflexão.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
    } catch (e: InvocationTargetException) {
        throw (e.targetException ?: e)
    }

    /** Executa um comando via Shizuku e devolve a saída binária (usado para captura PNG). */
    fun execBytes(command: String): ByteArray {
        val process = start(command)
        val out = process.inputStream.readBytes()
        // Consumir stderr evita deixar o processo bloqueado se houver saída de erro.
        process.errorStream.use { it.readBytes() }
        process.waitFor()
        return out
    }

    /** Executa um comando via Shizuku e devolve código, saída e erro (não lança por código != 0). */
    fun exec(command: String): Result {
        val process = start(command)
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        return Result(code, out.trim(), err.trim())
    }

    /** Executa e lança exceção se o comando falhar. */
    fun run(command: String): String {
        val r = exec(command)
        if (r.code != 0) {
            throw IllegalStateException(
                r.err.ifBlank { r.out.ifBlank { "comando terminou com código ${r.code}" } }
            )
        }
        return r.out
    }

    private val PACKAGE_REGEX = Regex("^[A-Za-z0-9_.]+$")
    fun isValidPackage(pkg: String): Boolean = PACKAGE_REGEX.matches(pkg)
}
