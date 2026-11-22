package com.appcontroller.tool

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private companion object {
        val View.gold get() = context.getColor(R.color.app_gold)
        val View.canvas get() = context.getColor(R.color.app_canvas)
        val View.surface get() = context.getColor(R.color.app_surface)
        val View.border get() = context.getColor(R.color.app_border)
        val View.ivory get() = context.getColor(R.color.app_text)
        val View.muted get() = context.getColor(R.color.app_muted)

        fun View.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

        fun View.rounded(fill: Int, radius: Int, outline: Int? = null) =
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(radius).toFloat()
                if (outline != null) setStroke(dp(1), outline)
            }

        fun Button.style(filled: Boolean, compact: Boolean = false) {
            val fill = if (filled) gold else surface
            val text = if (filled) canvas else gold
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
            val shape = rounded(fill, 10, if (filled) null else border).apply {
                setColor(ColorStateList(states, intArrayOf(context.getColor(R.color.app_disabled), fill)))
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
            val horizontalPadding = dp(if (compact) 4 else 12)
            setPadding(horizontalPadding, dp(8), horizontalPadding, dp(8))
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
    private lateinit var updateBanner: Button
    private var availableVersion: String? = null
    private var checkingUpdate = false
    private val installedVersion by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }
    private var showSelectedOnly = false
    private val preferences by lazy { getSharedPreferences("app_selection", MODE_PRIVATE) }
    private val selectedPackages by lazy {
        preferences.getStringSet("selected_packages", emptySet()).orEmpty().toMutableSet()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (preferences.getBoolean("dark_mode", true)) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
            val settingsButton = ImageButton(context).apply {
                contentDescription = "الإعدادات"
                setImageResource(R.drawable.ic_settings)
                imageTintList = ColorStateList.valueOf(gold)
                background = RippleDrawable(ColorStateList.valueOf(0x33F3C969), rounded(surface, 10, border), null)
                setOnClickListener { showAppSettings() }
            }
            addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(Button(context).apply {
                text = "المجموعات"
                style(filled = false)
                setOnClickListener { showGroups() }
            })
        }
        root.addView(header)
        updateBanner = Button(this).apply {
            visibility = View.GONE
            style(filled = true)
            setOnClickListener { openWebsite(AppUpdates.RELEASES_PAGE) }
        }
        root.addView(updateBanner)

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
            style(filled = true, compact = true)
            setOnClickListener {
                triggerForceStop(allApps.filter { it.packageName in selectedPackages })
            }
        }
        clearSelectionBtn = Button(this).apply {
            text = "إلغاء التحديد"
            style(filled = false, compact = true)
            setOnClickListener {
                preferences.edit().remove("active_group").apply()
                selectedPackages.clear()
                saveSelection()
                filterApps()
            }
        }
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
        val filterButton = Button(this).apply {
            text = "عرض المحدد"
            style(filled = false, compact = true)
            setOnClickListener {
                showSelectedOnly = !showSelectedOnly
                text = if (showSelectedOnly) "عرض الكل" else "عرض المحدد"
                style(filled = showSelectedOnly, compact = true)
                filterApps()
            }
        }
        listOf(filterButton, clearSelectionBtn, stopSelectedBtn).forEachIndexed { index, button ->
            button.textSize = 12f
            selectionActions.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index < 2) marginEnd = selectionActions.dp(6)
            })
        }
        root.addView(selectionActions)
        root.addView(searchInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = searchInput.dp(12) })

        adapter = AppsAdapter(
            mutableListOf(),
            isSelected = { it.packageName in selectedPackages },
            onSelectionChanged = { app, checked ->
                if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
                saveSelection(updateGroup = true)
                if (showSelectedOnly) filterApps()
            },
            onForceStop = { app -> triggerForceStop(listOf(app)) }
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
        checkForUpdate()
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
        statusBanner.visibility = if (enabled) View.GONE else View.VISIBLE
        enableServiceBtn.visibility = statusBanner.visibility
        if (!enabled) {
            statusBanner.text = "فعّل خدمة إمكانية الوصول لاستخدام فرض الإيقاف."
            statusBanner.background = statusBanner.rounded(statusBanner.surface, 12, statusBanner.border)
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

    override fun onPostResume() {
        super.onPostResume()
        if (intent.getBooleanExtra(ForceStopAccessibilityService.EXTRA_CONTINUE, false)) {
            intent.removeExtra(ForceStopAccessibilityService.EXTRA_CONTINUE)
            ForceStopAccessibilityService.continueQueue(this)
        }
    }

    private fun showGroups() {
        val groups = loadGroups()
        val names = groups.keys().asSequence().sorted().toList()
        val selected = allApps.filter { it.packageName in selectedPackages }
        val dialog = AlertDialog.Builder(this)
            .setTitle("المجموعات")
            .setNegativeButton("إغلاق", null)
            .setNeutralButton("حفظ المحدد كمجموعة") { _, _ -> saveGroup(groups, selected) }
        if (names.isEmpty()) {
            dialog.setMessage("حدد التطبيقات التي تريد جمعها، ثم احفظها باسم مجموعة.")
        } else {
            dialog.setPositiveButton("حذف مجموعة") { _, _ -> showDeleteGroup(groups, names) }
            dialog.setItems(names.toTypedArray()) { _, index ->
                val name = names[index]
                val packages = groups.getJSONArray(name)
                selectedPackages.clear()
                selectedPackages.addAll((0 until packages.length()).map { packages.getString(it) })
                preferences.edit().putString("active_group", name).apply()
                saveSelection()
                filterApps()
            }
        }
        dialog.show().getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = selected.isNotEmpty()
    }

    private fun loadGroups() = JSONObject(preferences.getString("app_groups", "{}") ?: "{}")

    private fun showDeleteGroup(groups: JSONObject, names: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("اختر المجموعة المراد حذفها")
            .setItems(names.toTypedArray()) { _, index ->
                val name = names[index]
                AlertDialog.Builder(this)
                    .setTitle("حذف مجموعة «$name»؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف") { _, _ ->
                        groups.remove(name)
                        val edit = preferences.edit().putString("app_groups", groups.toString())
                        if (preferences.getString("active_group", null) == name) edit.remove("active_group")
                        edit.apply()
                        showGroups()
                    }
                    .show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun saveGroup(groups: JSONObject, apps: List<AppItem>) {
        val input = EditText(this).apply {
            hint = "مثال: ألعاب"
            isSingleLine = true
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("اسم المجموعة")
            .setView(input)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            when {
                name.isEmpty() -> input.error = "أدخل اسم المجموعة"
                groups.has(name) -> input.error = "يوجد مجموعة بهذا الاسم"
                else -> {
                    groups.put(name, JSONArray(apps.map { it.packageName }))
                    preferences.edit()
                        .putString("app_groups", groups.toString())
                        .putString("active_group", name)
                        .apply()
                    dialog.dismiss()
                    showGroups()
                }
            }
        }
    }

    private fun showAppSettings() {
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(TextView(context).apply {
                text = "الإصدار $installedVersion"
                setTextColor(gold)
                textSize = 14f
            })
            addView(TextView(context).apply {
                text = "عن التطبيق"
                setTextColor(ivory)
                textSize = 18f
                setPadding(0, dp(16), 0, dp(6))
            })
            addView(TextView(context).apply {
                text = "ابحث عن تطبيقاتك، واحفظها في مجموعات، وأوقف تطبيقًا واحدًا أو عدة تطبيقات عبر خدمة إمكانية الوصول."
                setTextColor(muted)
                textSize = 15f
            })
            addView(Switch(context).apply {
                text = "الوضع الداكن"
                isChecked = preferences.getBoolean("dark_mode", true)
                minHeight = dp(52)
                setOnCheckedChangeListener { _, checked ->
                    preferences.edit().putBoolean("dark_mode", checked).apply()
                    delegate.localNightMode = if (checked) AppCompatDelegate.MODE_NIGHT_YES
                        else AppCompatDelegate.MODE_NIGHT_NO
                }
            })
            addView(Button(context).apply {
                val latest = availableVersion
                text = if (latest == null) "التحقق من التحديث" else "تنزيل تحديث $latest"
                style(filled = false)
                setOnClickListener {
                    if (availableVersion != null) openWebsite(AppUpdates.RELEASES_PAGE)
                    else checkForUpdate(showResult = true)
                }
            })
        }
        AlertDialog.Builder(this)
            .setTitle("إعدادات التطبيق")
            .setView(details)
            .setPositiveButton("المطور Sameer LAB") { _, _ ->
                openWebsite("https://sameerlab.com")
            }
            .setNegativeButton("إغلاق", null)
            .show()
            .apply {
                window?.setBackgroundDrawable(statusBanner.rounded(statusBanner.surface, 16, statusBanner.border))
                getButton(AlertDialog.BUTTON_POSITIVE).style(filled = true)
            }
    }

    private fun openWebsite(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "لا يوجد متصفح لفتح الموقع", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForUpdate(showResult: Boolean = false) {
        if (checkingUpdate) {
            if (showResult) Toast.makeText(this, "جارٍ التحقق من التحديث…", Toast.LENGTH_SHORT).show()
            return
        }
        checkingUpdate = true
        val version = installedVersion
        Thread {
            val result = runCatching { AppUpdates.newerVersion(version) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                checkingUpdate = false
                val latest = result.getOrNull()
                if (result.isSuccess) availableVersion = latest
                if (latest != null) {
                    updateBanner.text = "يتوفر تحديث $latest"
                    updateBanner.visibility = View.VISIBLE
                    if (showResult) {
                        AlertDialog.Builder(this)
                            .setTitle("يتوفر تحديث $latest")
                            .setPositiveButton("صفحة التنزيل") { _, _ -> openWebsite(AppUpdates.RELEASES_PAGE) }
                            .setNegativeButton("لاحقًا", null)
                            .show()
                    }
                } else if (showResult) {
                    val message = if (result.isSuccess) "لا يوجد إصدار أحدث متاح"
                        else "تعذّر التحقق من التحديث. حاول مجددًا."
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
                window?.setBackgroundDrawable(statusBanner.rounded(statusBanner.surface, 16, statusBanner.border))
                getButton(AlertDialog.BUTTON_POSITIVE).style(filled = true)
            }
    }

    private fun saveSelection(updateGroup: Boolean = false) {
        val selection = selectedPackages.toSet()
        val edit = preferences.edit().putStringSet("selected_packages", selection)
        val group = if (updateGroup) preferences.getString("active_group", null) else null
        if (group != null) {
            val groups = loadGroups()
            groups.put(group, JSONArray(selection.toList()))
            edit.putString("app_groups", groups.toString())
        }
        edit.apply()
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
            (!showSelectedOnly || it.packageName in selectedPackages) &&
                (it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query))
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
        private val onForceStop: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

        class AppViewHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            lateinit var selection: CheckBox
            lateinit var iconView: ImageView
            lateinit var nameView: TextView
            lateinit var stopBtn: ImageButton
        }

        fun updateList(newList: List<AppItem>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(surface, 14, border)
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(1)
                    bottomMargin = dp(1)
                }
            }
            val iconView = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            }

            val nameView = TextView(ctx).apply {
                setTextColor(ivory)
                textSize = 15f
                setPaddingRelative(dp(10), 0, dp(6), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val stopBtn = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_stop)
                imageTintList = ColorStateList.valueOf(gold)
                background = RippleDrawable(ColorStateList.valueOf(0x33F3C969), rounded(canvas, 10, border), null)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }

            val selection = CheckBox(ctx)
            row.addView(selection)
            row.addView(iconView)
            row.addView(nameView)
            row.addView(stopBtn)

            return AppViewHolder(row).apply {
                this.selection = selection
                this.iconView = iconView
                this.nameView = nameView
                this.stopBtn = stopBtn
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
            holder.stopBtn.contentDescription = "فرض إيقاف "
            holder.stopBtn.setOnClickListener { onForceStop(item) }

        }

        override fun getItemCount(): Int = items.size
    }
}
