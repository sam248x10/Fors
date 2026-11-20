package com.appcontroller.tool

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var statusBanner: TextView
    private lateinit var enableServiceBtn: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var allApps: List<AppItem>
    private lateinit var adapter: AppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1020"))
            setPadding(24, 24, 24, 24)
        }

        // شريط حالة صلاحية الوصول
        statusBanner = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(24, 20, 24, 20)
        }
        root.addView(statusBanner)

        // زر تفعيل صلاحية إمكانية الوصول مباشرة
        enableServiceBtn = Button(this).apply {
            text = "تفعيل صلاحية إمكانية الوصول (مطلوب لفرض الإيقاف)"
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#7C3AED"), Color.parseColor("#2563EB"), Color.parseColor("#0D9488"))
            )
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16; bottomMargin = 16 }
        root.addView(enableServiceBtn, btnParams)

        // حقل بحث سريع عن التطبيق (مثل X أو تويتر)
        val searchInput = EditText(this).apply {
            hint = "ابحث عن واتساب أو إكس..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#162038"))
            setPadding(24, 20, 24, 20)
            textSize = 15f
        }
        val searchParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 }
        root.addView(searchInput, searchParams)

        allApps = loadInstalledApps()
        adapter = AppsAdapter(
            allApps.toMutableList(),
            onForceStop = { app -> triggerForceStop(app) },
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
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                val filtered = if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter {
                        it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                    }
                }
                adapter.updateList(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ForceStopAccessibilityService.clear()
        updateServiceStatusUI()
    }

    private fun updateServiceStatusUI() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            statusBanner.text = "إمكانية الوصول مفعّلة. يمكنك إيقاف واتساب وإكس أو تشغيلهما."
            statusBanner.setBackgroundColor(Color.parseColor("#0F766E"))
            enableServiceBtn.visibility = View.GONE
        } else {
            statusBanner.text = "الحالة: صلاحية إمكانية الوصول غير مفعّلة بعد"
            statusBanner.setBackgroundColor(Color.parseColor("#991B1B"))
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

    private fun triggerForceStop(app: AppItem) {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "فعّل صلاحية إمكانية الوصول أولاً", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", app.packageName, null)
        )
        val settingsPackage = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        if (settingsPackage == null) {
            Toast.makeText(this, "تعذّر فتح إعدادات التطبيق", Toast.LENGTH_SHORT).show()
            return
        }

        ForceStopAccessibilityService.prepare(app, settingsPackage)
        try {
            startActivity(intent.setPackage(settingsPackage))
        } catch (_: android.content.ActivityNotFoundException) {
            ForceStopAccessibilityService.clear()
            Toast.makeText(this, "تعذّر فتح إعدادات التطبيق", Toast.LENGTH_SHORT).show()
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
        val supportedPackages = setOf("com.whatsapp", "com.twitter.android")
        val packages = pm.getInstalledApplications(0)
        return packages
            .filter { it.packageName in supportedPackages && pm.getLaunchIntentForPackage(it.packageName) != null }
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
        private val onForceStop: (AppItem) -> Unit,
        private val onLaunch: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

        class AppViewHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
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
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#131C31"))
                setPadding(18, 16, 18, 16)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12 }
            }

            val iconView = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(84, 84)
            }

            val nameView = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(16, 0, 12, 0)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val stopBtn = Button(ctx).apply {
                text = "إيقاف"
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DC2626"))
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = 8 }
            }

            val launchBtn = Button(ctx).apply {
                text = "تشغيل"
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#0D9488"))
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(iconView)
            row.addView(nameView)
            row.addView(stopBtn)
            row.addView(launchBtn)

            return AppViewHolder(row).apply {
                this.iconView = iconView
                this.nameView = nameView
                this.stopBtn = stopBtn
                this.launchBtn = launchBtn
            }
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val item = items[position]
            holder.iconView.setImageDrawable(item.icon)
            holder.nameView.text = item.name
            holder.stopBtn.setOnClickListener { onForceStop(item) }
            holder.launchBtn.setOnClickListener { onLaunch(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
