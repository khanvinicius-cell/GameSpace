package dev.resshortcut.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.TextUtils
import android.widget.*
import java.util.Locale

data class AppInfo(val label: String, val pkg: String, val icon: Drawable)

object Apps {
    /** Apps com ícone no launcher (exceto o próprio Vyka R-L), em ordem alfabética. */
    fun load(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull {
                val pkg = it.activityInfo.packageName
                if (pkg == ctx.packageName) null else AppInfo(it.loadLabel(pm).toString(), pkg, it.loadIcon(pm))
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }
}

object Pickers {
    /** Lista de exceções da limpeza: apps marcados nunca são encerrados. */
    fun whitelist(activity: Activity, apps: List<AppInfo>, onSaved: () -> Unit) {
        val ui = Ui(activity)
        val skip = Cleaner.protectedPackages(activity, null)
        val options = apps.filter { it.pkg !in skip }
        if (options.isEmpty()) {
            Toast.makeText(activity, "Nenhum app disponível.", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = Cleaner.getWhitelist(activity).toMutableSet()

        val listAdapter = object : BaseAdapter() {
            override fun getCount() = options.size
            override fun getItem(position: Int) = options[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = options[position]
                val row = (convertView as? LinearLayout)?.takeIf { it.childCount == 3 } ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                        LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
                    addView(ui.label("", 15f).apply {
                        setSingleLine()
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = ui.dp(12) })
                    addView(CheckBox(activity).apply {
                        isClickable = false
                        isFocusable = false
                    })
                }
                (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                (row.getChildAt(1) as TextView).text = app.label
                (row.getChildAt(2) as CheckBox).isChecked = app.pkg in selected
                row.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
                return row
            }
        }
        val list = ListView(activity)
        list.divider = null
        list.adapter = listAdapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val pkg = options[pos].pkg
            if (!selected.add(pkg)) selected.remove(pkg)
            listAdapter.notifyDataSetChanged()
        }

        AlertDialog.Builder(activity, R.style.VykaDialog)
            .setTitle("Lista de exceções da limpeza")
            .setView(list)
            .setPositiveButton("Salvar") { _, _ ->
                Cleaner.setWhitelist(activity, selected)
                onSaved()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
