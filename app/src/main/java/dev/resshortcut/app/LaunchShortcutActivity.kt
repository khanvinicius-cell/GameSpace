package dev.resshortcut.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LaunchShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se o sistema recriar esta tela, não executa tudo de novo.
        if (savedInstanceState != null) {
            finish()
            return
        }
        Thread { execute() }.start()
    }

    private fun execute() {
        try {
            if (!ShizukuShell.isReady()) {
                toast("Abra o Vyka R e autorize o Shizuku.")
                finishOnUi()
                return
            }
            if (intent.getBooleanExtra("reset", false)) {
                stopService(Intent(this, RestoreService::class.java))
                DisplayControl.restore(this)
                toast("Resolução padrão restaurada.")
                finishOnUi()
                return
            }

            val pkg = intent.getStringExtra("package")
                ?: throw IllegalArgumentException("Pacote ausente")
            if (!ShizukuShell.isValidPackage(pkg)) throw IllegalArgumentException("Pacote inválido")

            // Atalhos antigos só têm "scale"/"clean": os padrões abaixo os mantêm funcionando.
            val match = intent.getBooleanExtra("match", true)
            val pct = intent.getIntExtra("scale", 50)
            val cw = intent.getIntExtra("w", 0)
            val ch = intent.getIntExtra("h", 0)
            val dpiMode = intent.getIntExtra("dpiMode", Plan.DPI_KEEP).coerceIn(0, 2)
            val customDpi = intent.getIntExtra("dpi", 0)
            val clean = intent.getIntExtra("clean", Cleaner.MODE_NONE).coerceIn(0, 2)
            val anim = intent.getBooleanExtra("anim", false)
            val boost = Boost.Opts(
                gameMode = intent.getBooleanExtra("gm", false),
                noAnim = intent.getBooleanExtra("na", false),
                dnd = intent.getBooleanExtra("dnd", false),
                noSaver = intent.getBooleanExtra("ns", false),
                hz = intent.getIntExtra("hz", 0)
            )

            val output = ShizukuShell.run("wm size")
            val physical = Regex("Physical size: (\\d+)x(\\d+)").find(output)
                ?: Regex("Override size: (\\d+)x(\\d+)").find(output)
                ?: throw IllegalStateException("Não consegui identificar a resolução atual: $output")
            val physW = physical.groupValues[1].toInt()
            val physH = physical.groupValues[2].toInt()
            val physDpi = if (dpiMode != Plan.DPI_KEEP) {
                Regex("Physical density: (\\d+)").find(ShizukuShell.run("wm density"))
                    ?.groupValues?.get(1)?.toInt() ?: 0
            } else 0

            val plan = Plan.compute(physW, physH, physDpi, match, pct, cw, ch, dpiMode, customDpi)

            val launch = packageManager.getLaunchIntentForPackage(pkg)
                ?: throw IllegalStateException("O app $pkg não foi encontrado ou está desativado/congelado.")

            // 1) Animação mostrando quanto a resolução vai ser reduzida (antes da mudança).
            if (anim) {
                val latch = CountDownLatch(1)
                runOnUiThread { showReduction(physW, physH, physDpi, plan) { latch.countDown() } }
                latch.await(4, TimeUnit.SECONDS)
            }

            // 2) Limpeza rápida de cache, antes de mudar a tela.
            if (clean != Cleaner.MODE_NONE) {
                try {
                    val n = Cleaner.run(this, clean, pkg)
                    if (clean == Cleaner.MODE_WHITELIST) toast("Limpeza: $n app(s) encerrado(s).")
                } catch (e: Exception) {
                    toast("Limpeza falhou: ${e.message}")
                }
            }

            // 3) Serviço que restaura a resolução quando o jogo fechar.
            try {
                startForegroundService(
                    Intent(this, RestoreService::class.java).putExtra(RestoreService.EXTRA_PACKAGE, pkg)
                        .putExtra(RestoreService.EXTRA_RESTORE_DISPLAY, true)
                )
            } catch (e: Exception) {
                toast("Restauração automática indisponível: ${e.message}")
            }

            // 4) Aplica somente o método tradicional de resolução global.
            DisplayControl.apply(this, plan)

            // 5) Otimizações opcionais (desfeitas quando o jogo fechar).
            if (boost.any()) {
                try {
                    val n = Boost.apply(this, pkg, boost)
                    toast("Otimizações aplicadas: $n")
                } catch (e: Exception) {
                    toast("Otimizações falharam: ${e.message}")
                }
            }
            runOnUiThread {
                try {
                    applicationContext.startActivity(launch)
                } catch (e: Exception) {
                    toast("Falha ao abrir o jogo: ${e.message}")
                }
                finish()
            }
        } catch (e: Exception) {
            toast("Falha: ${e.message}")
            // Aberto pelo launcher: se algo falhar antes de mudar a tela, abre o app normalmente.
            if (intent.getBooleanExtra("fallback", false)) {
                intent.getStringExtra("package")
                    ?.let { packageManager.getLaunchIntentForPackage(it) }
                    ?.let { i -> runOnUiThread { try { applicationContext.startActivity(i) } catch (_: Exception) {} } }
            }
            finishOnUi()
        }
    }

    private fun showReduction(physW: Int, physH: Int, physDpi: Int, plan: Plan.Result, onEnd: () -> Unit) {
        val ratio = (plan.w.toDouble() * plan.h) / (physW.toDouble() * physH)
        val linear = sqrt(ratio).toFloat()
        val accent = Color.parseColor("#6FCF97")

        val big = TextView(this).apply {
            text = "100%"
            textSize = 64f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "da resolução nativa"
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
            gravity = Gravity.CENTER
        }
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A2A"))
                cornerRadius = dp(4).toFloat()
            }
        }
        val fill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(4).toFloat()
            }
            pivotX = 0f
        }
        track.addView(fill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val saved = ((1.0 - ratio) * 100).roundToInt()
        val details = StringBuilder("${physW}×${physH}  →  ${plan.w}×${plan.h}\n−$saved% de pixels")
        if (plan.dpi != null) {
            details.append("\nDPI ").append(if (physDpi > 0) "$physDpi  →  ${plan.dpi}" else "${plan.dpi}")
        }
        val info = TextView(this).apply {
            text = details.toString()
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        col.addView(big)
        col.addView(sub)
        col.addView(track, LinearLayout.LayoutParams(dp(240), dp(8)).apply { setMargins(0, dp(20), 0, dp(20)) })
        col.addView(info)

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            alpha = 0f
        }
        root.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        setContentView(root)

        val animator = ValueAnimator.ofFloat(1f, linear).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                big.text = "${(f * 100).roundToInt()}%"
                fill.scaleX = f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.postDelayed({
                        root.animate().alpha(0f).setDuration(150).withEndAction { onEnd() }.start()
                    }, 450)
                }
            })
        }
        root.animate().alpha(1f).setDuration(150).withEndAction { animator.start() }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun finishOnUi() = runOnUiThread { finish() }

    private fun toast(s: String) = runOnUiThread {
        Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()
    }
}
