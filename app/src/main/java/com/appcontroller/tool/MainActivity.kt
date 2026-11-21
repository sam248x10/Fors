package com.appcontroller.tool

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private companion object {
        val gold = Color.parseColor("#F3C969")
        val canvas = Color.parseColor("#101010")
        val surface = Color.parseColor("#1D1A15")
        val border = Color.parseColor("#58472C")
        val ivory = Color.parseColor("#FFF4DD")
        val muted = Color.parseColor("#C8BDA8")

        fun View.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

        fun View.rounded(fill: Int, radius: Int, outline: Int? = null) =
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(radius).toFloat()
                if (outline != null) setStroke(dp(1), outline)
            }

        fun Button.style(filled: Boolean) {
            val fill = if (filled) gold else surface
            val text = if (filled) canvas else gold
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
            val shape = rounded(fill, 10, if (filled) null else border).apply {
                setColor(ColorStateList(states, intArrayOf(Color.parseColor("#29251E"), fill)))
            }
            backgroundTintList = null
            background = RippleDrawable(
                ColorStateList.valueOf(if (filled) 0x22101010 else 0x33F3C969),
                shape,
                null
            )
            setTextColor(ColorStateList(states, intArrayOf(muted, text)))
            isAllCaps = false
            minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
    }

    private lateinit var statusBanner: TextView
    private lateinit var enableServiceBtn: Button
    private lateinit var recyclerView: RecyclerView
    private var allApps = emptyList<AppItem>()
    private lateinit var adapter: AppsAdapter
    private lateinit var stopSelectedBtn: Button
    private lateinit var clearSelectionBtn: Button
    private lateinit var searchInput: EditText
    private val preferences by lazy { getSharedPreferences("app_selection", MODE_PRIVATE) }
    private val selectedPackages by lazy {
        preferences.getStringSet("selected_packages", emptySet()).orEmpty().toMutableSet()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        // شريط حالة صلاحية الوصول
        statusBanner = TextView(this).apply {
            textSize = 15f
            setTextColor(ivory)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(statusBanner)

        // زر تفعيل صلاحية إمكانية الوصول مباشرة
        enableServiceBtn = Button(this).apply {
            text = "تفعيل صلاحية إمكانية الوصول (مطلوب لفرض الإيقاف)"
            textSize = 15f
            style(filled = false)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16; bottomMargin = 16 }
        root.addView(enableServiceBtn, btnParams)

        val selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        stopSelectedBtn = Button(this).apply {
            style(filled = true)
            setOnClickListener {
                triggerForceStop(allApps.filter { it.packageName in selectedPackages })
            }
        }
        clearSelectionBtn = Button(this).apply {
            text = "إلغاء التحديد"
            style(filled = false)
            setOnClickListener {
                selectedPackages.clear()
                saveSelection()
                adapter.notifyDataSetChanged()
            }
        }
        selectionActions.addView(
            stopSelectedBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = selectionActions.dp(8)
            }
        )
        selectionActions.addView(
            clearSelectionBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(selectionActions)

        // البحث بالاسم أو اسم الحزمة
        searchInput = EditText(this).apply {
            hint = "ابحث عن تطبيق..."
            setHintTextColor(muted)
            setTextColor(ivory)
            background = rounded(surface, 12, border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = true
            textSize = 15f
        }
        val searchParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 }
        root.addView(searchInput, searchParams)

        adapter = AppsAdapter(
            mutableListOf(),
            isSelected = { it.packageName in selectedPackages },
            onSelectionChanged = { app, checked ->
                if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
                saveSelection()
            },
            onForceStop = { app -> triggerForceStop(listOf(app)) },
            onLaunch = { app -> triggerLaunch(app) }
        )

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        root.addView(
            recyclerView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        allApps = loadInstalledApps()
        filterApps()
        updateSelectionActions()
        showStopResult()
        updateServiceStatusUI()
    }

    private fun updateServiceStatusUI() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            statusBanner.text = "إمكانية الوصول مفعّلة. يمكنك إيقاف التطبيقات المحددة."
            statusBanner.background = statusBanner.rounded(surface, 12, border)
            enableServiceBtn.visibility = View.GONE
        } else {
            statusBanner.text = "الحالة: صلاحية إمكانية الوصول غير مفعّلة بعد"
            statusBanner.background = statusBanner.rounded(Color.parseColor("#322617"), 12, gold)
            enableServiceBtn.visibility = View.VISIBLE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${ForceStopAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(expectedComponent, ignoreCase = true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showStopResult()
    }

    private fun showStopResult() {
        val message = intent.getStringExtra(ForceStopAccessibilityService.EXTRA_RESULT) ?: return
        intent.removeExtra(ForceStopAccessibilityService.EXTRA_RESULT)
        AlertDialog.Builder(this)
            .setTitle("نتيجة الإيقاف")
            .setMessage(message)
            .setPositiveButton("تم", null)
            .show()
            .apply {
                window?.setBackgroundDrawable(statusBanner.rounded(surface, 16, border))
                getButton(AlertDialog.BUTTON_POSITIVE).style(filled = true)
            }
    }

    private fun saveSelection() {
        preferences.edit().putStringSet("selected_packages", selectedPackages.toSet()).apply()
        updateSelectionActions()
    }

    private fun updateSelectionActions() {
        val count = allApps.count { it.packageName in selectedPackages }
        stopSelectedBtn.text = "إيقاف المحدد ($count)"
        stopSelectedBtn.isEnabled = count > 0
        clearSelectionBtn.isEnabled = selectedPackages.isNotEmpty()
    }

    private fun filterApps() {
        val query = searchInput.text.toString().trim().lowercase()
        adapter.updateList(allApps.filter {
            it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        })
    }

    private fun triggerForceStop(apps: List<AppItem>) {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "فعّل صلاحية إمكانية الوصول أولاً", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (!ForceStopAccessibilityService.enqueue(this, apps)) {
            Toast.makeText(this, "تعذّر بدء الإيقاف. انتظر انتهاء العملية أو تحقق من تفعيل الخدمة.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerLaunch(app: AppItem) {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "لا يمكن تشغيل هذا التطبيق مباشرة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadInstalledApps(): List<AppItem> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { appInfo ->
                AppItem(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    icon = pm.getApplicationIcon(appInfo)
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private class AppsAdapter(
        private val items: MutableList<AppItem>,
        private val isSelected: (AppItem) -> Boolean,
        private val onSelectionChanged: (AppItem, Boolean) -> Unit,
        private val onForceStop: (AppItem) -> Unit,
        private val onLaunch: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

        class AppViewHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            lateinit var selection: CheckBox
            lateinit var iconView: ImageView
            lateinit var nameView: TextView
            lateinit var stopBtn: Button
            lateinit var launchBtn: Button
        }

        fun updateList(newList: List<AppItem>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(surface, 14, border)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val appDetails = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val appActions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val iconView = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            }

            val nameView = TextView(ctx).apply {
                setTextColor(ivory)
                textSize = 15f
                setPaddingRelative(dp(10), 0, dp(6), 0)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val stopBtn = Button(ctx).apply {
                text = "إيقاف"
                textSize = 13f
                style(filled = false)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }

            val launchBtn = Button(ctx).apply {
                text = "تشغيل"
                textSize = 13f
                style(filled = true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val selection = CheckBox(ctx)
            appDetails.addView(selection)
            appDetails.addView(iconView)
            appDetails.addView(nameView)
            appActions.addView(stopBtn)
            appActions.addView(launchBtn)
            row.addView(appDetails)
            row.addView(appActions)

            return AppViewHolder(row).apply {
                this.selection = selection
                this.iconView = iconView
                this.nameView = nameView
                this.stopBtn = stopBtn
                this.launchBtn = launchBtn
            }
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val item = items[position]
            holder.selection.setOnCheckedChangeListener(null)
            holder.selection.isChecked = isSelected(item)
            holder.selection.contentDescription = "تحديد ${item.name}"
            holder.selection.setOnCheckedChangeListener { _, checked -> onSelectionChanged(item, checked) }
            holder.iconView.setImageDrawable(item.icon)
            holder.nameView.text = item.name
            holder.stopBtn.setOnClickListener { onForceStop(item) }
            holder.launchBtn.setOnClickListener { onLaunch(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
