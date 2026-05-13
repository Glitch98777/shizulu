package com.shizulu.manager

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val store by lazy { ShizuleStore(this) }
    private val logPrefs by lazy { getSharedPreferences("shizulu_logs", MODE_PRIVATE) }
    private val settingsPrefs by lazy { getSharedPreferences("shizulu_settings", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var shizukuChip: TextView
    private lateinit var permissionChip: TextView
    private lateinit var serviceChip: TextView
    private lateinit var backendChip: TextView
    private lateinit var grantButton: TextView
    private lateinit var dryRunButton: TextView
    private lateinit var moduleCount: TextView
    private lateinit var moduleSummaryText: TextView
    private lateinit var profileSummaryText: TextView
    private lateinit var modeSummaryText: TextView
    private lateinit var updaterStatusText: TextView
    private lateinit var updaterButton: TextView
    private lateinit var lightModeButton: TextView
    private lateinit var darkModeButton: TextView
    private lateinit var profilesList: LinearLayout
    private lateinit var moduleList: LinearLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var homeNav: LinearLayout
    private lateinit var modulesNav: LinearLayout
    private lateinit var profilesNav: LinearLayout
    private lateinit var toolsNav: LinearLayout
    private var service: IShizuluService? = null
    private var dryRunEnabled = false
    private var executionMode = ExecutionMode.SHIZUKU
    private var currentPage = Page.HOME
    private var latestRelease: GithubRelease? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            renderStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) bindUserService()
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        renderStatus()
        if (hasShizukuPermission()) bindUserService()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        renderStatus()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShizuluService.Stub.asInterface(binder)
            renderStatus()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            renderStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        dryRunEnabled = settingsPrefs.getBoolean(KEY_DRY_RUN, false)
        executionMode = ExecutionMode.from(settingsPrefs.getString(KEY_EXECUTION_MODE, null))
        applyThemeFromPrefs()
        tuneWindow()
        buildUi()
        renderStatus()
        renderDryRun()
        refreshModules()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Kept for the platform document picker callback.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK) {
            data?.data?.let(::installFromUri)
        }
        if (requestCode == REQUEST_BACKUP_CREATE && resultCode == RESULT_OK) {
            data?.data?.let(::writeBackupToUri)
        }
        if (requestCode == REQUEST_BACKUP_OPEN && resultCode == RESULT_OK) {
            data?.data?.let(::restoreBackupFromUri)
        }
    }

    private fun tuneWindow() {
        window.statusBarColor = COLORS.background
        window.navigationBarColor = COLORS.background
        window.decorView.systemUiVisibility = if (COLORS.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    private fun buildUi() {
        tuneWindow()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLORS.background)
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            addView(appHeader())
        })

        contentHost = FrameLayout(this)
        root.addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNav())

        setContentView(root)
        showPage(Page.HOME)
    }

    private fun showPage(page: Page) {
        currentPage = page
        contentHost.removeAllViews()
        contentHost.addView(pageScroll(page), FrameLayout.LayoutParams(-1, -1))
        renderNav()
        renderStatus()
        renderDryRun()
        refreshModules()
    }

    private fun pageScroll(page: Page): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLORS.background)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(18))
        }
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))

        when (page) {
            Page.HOME -> {
                content.addView(sectionTitle("Dashboard"), spacedParams(top = 8))
                content.addView(statusPanel(), spacedParams(top = 10))
                content.addView(summaryStrip(), spacedParams(top = 12))
                content.addView(sectionTitle("Quick Actions"), spacedParams(top = 24))
                content.addView(primaryActions(), spacedParams(top = 10))
            }
            Page.MODULES -> {
                content.addView(sectionHeader(), spacedParams(top = 8))
                moduleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                content.addView(moduleList, spacedParams(top = 10))
            }
            Page.PROFILES -> {
                content.addView(profilesHeader(), spacedParams(top = 8))
                profilesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                content.addView(profilesList, spacedParams(top = 10))
            }
            Page.TOOLS -> {
                content.addView(sectionTitle("Tools"), spacedParams(top = 8))
                content.addView(logsFooter(), spacedParams(top = 10))
            }
        }
        return scroll
    }

    private fun bottomNav(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = roundedRect(COLORS.surface, dp(0), COLORS.outline, 1)

            homeNav = navButton(Page.HOME, "Home") { showPage(Page.HOME) }
            modulesNav = navButton(Page.MODULES, "Modules") { showPage(Page.MODULES) }
            profilesNav = navButton(Page.PROFILES, "Profiles") { showPage(Page.PROFILES) }
            toolsNav = navButton(Page.TOOLS, "Tools") { showPage(Page.TOOLS) }

            addView(homeNav, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(modulesNav, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(profilesNav, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(toolsNav, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
    }

    private fun navButton(page: Page, label: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(3))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(NavIconView(context, page), LinearLayout.LayoutParams(dp(28), dp(24)))
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun renderNav() {
        if (!::homeNav.isInitialized) return
        setNavState(homeNav, currentPage == Page.HOME)
        setNavState(modulesNav, currentPage == Page.MODULES)
        setNavState(profilesNav, currentPage == Page.PROFILES)
        setNavState(toolsNav, currentPage == Page.TOOLS)
    }

    private fun setNavState(view: LinearLayout, selected: Boolean) {
        val color = if (selected) COLORS.primary else COLORS.muted
        for (index in 0 until view.childCount) {
            when (val child = view.getChildAt(index)) {
                is TextView -> child.setTextColor(color)
                is NavIconView -> child.setIconColor(color)
            }
        }
        view.background = if (selected) roundedRect(COLORS.primarySoft, dp(8)) else null
    }

    private fun appHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = "S"
                textSize = 22f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = roundedRect(COLORS.primary, dp(16))
            }, LinearLayout.LayoutParams(dp(52), dp(52)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)

                addView(TextView(context).apply {
                    text = "Shizulu"
                    textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLORS.ink)
                    includeFontPadding = false
                })

                addView(TextView(context).apply {
                    text = "Rootless modules over Shizuku or Wireless ADB"
                    textSize = 14f
                    setTextColor(COLORS.muted)
                    includeFontPadding = false
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun sectionTitle(label: String): View {
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLORS.muted)
            setPadding(dp(2), 0, 0, 0)
            letterSpacing = 0.08f
        }
    }

    private fun statusPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = roundedRect(COLORS.surface, dp(8), COLORS.outline, 1)
            elevation = dp(1).toFloat()

            statusTitle = TextView(context).apply {
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
                includeFontPadding = false
            }
            addView(statusTitle)

            statusSubtitle = TextView(context).apply {
                textSize = 14f
                setTextColor(COLORS.muted)
                setPadding(0, dp(7), 0, dp(14))
            }
            addView(statusSubtitle)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                shizukuChip = chip("Shizuku")
                permissionChip = chip("Permission")
                serviceChip = chip("Service")
                backendChip = chip("Backend")

                addView(shizukuChip)
                addView(permissionChip, rowGapParams())
                addView(serviceChip, rowGapParams())
                addView(backendChip, rowGapParams())
            })
        }
    }

    private fun primaryActions(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedRect(COLORS.surface, dp(8), COLORS.outline, 1)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(primaryButton("Install shizule") { openJsonPicker() }, LinearLayout.LayoutParams(0, dp(48), 1f))

                grantButton = secondaryButton("Grant Shizuku") { requestShizukuPermission() }
                addView(grantButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    leftMargin = dp(10)
                })
            })

            addView(TextView(context).apply {
                text = "Install JSON modules, choose an execution backend, then run actions from Profiles or Installed shizules."
                textSize = 13f
                setTextColor(COLORS.muted)
                setPadding(dp(2), dp(12), dp(2), 0)
            })
        }
    }

    private fun summaryStrip(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            moduleSummaryText = summaryTile("0", "Modules")
            profileSummaryText = summaryTile("0", "Profiles")
            modeSummaryText = summaryTile("Live", "Mode")

            addView(moduleSummaryText, LinearLayout.LayoutParams(0, dp(72), 1f))
            addView(profileSummaryText, LinearLayout.LayoutParams(0, dp(72), 1f).apply { leftMargin = dp(10) })
            addView(modeSummaryText, LinearLayout.LayoutParams(0, dp(72), 1f).apply { leftMargin = dp(10) })
        }
    }

    private fun summaryTile(value: String, label: String): TextView {
        return TextView(this).apply {
            text = "$value\n$label"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLORS.ink)
            background = roundedRect(COLORS.surface, dp(8), COLORS.outline, 1)
        }
    }

    private fun profilesHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = "Profiles"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
            }, LinearLayout.LayoutParams(0, -2, 1f))

            dryRunButton = secondaryButton("Dry Run: Off") { toggleDryRun() }
            addView(dryRunButton, LinearLayout.LayoutParams(dp(128), dp(42)))
        }
    }

    private fun sectionHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = "Installed shizules"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
            }, LinearLayout.LayoutParams(0, -2, 1f))

            moduleCount = TextView(context).apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.secondary)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundedRect(COLORS.secondarySoft, dp(16))
            }
            addView(moduleCount)
        }
    }

    private fun logsFooter(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedRect(COLORS.surface, dp(8), COLORS.outline, 1)

            addView(executionModePanel())
            addView(wirelessAdbPanel(), spacedParams(top = 10))
            addView(updaterPanel(), spacedParams(top = 10))
            addView(appearancePanel(), spacedParams(top = 10))
            addView(secondaryButton("Logs") { showLogs() }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(secondaryButton("Backup") { openBackupCreator() }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(secondaryButton("Restore Backup") { openBackupPicker() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    leftMargin = dp(10)
                })
            }, spacedParams(top = 10))
        }
    }

    private fun executionModePanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))

            addView(TextView(context).apply {
                text = "Execution backend"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
            })

            addView(TextView(context).apply {
                text = "Shizuku is fully supported. Wireless ADB can now pair, discover the local ADB service, and run shizules without Shizuku."
                textSize = 13f
                setTextColor(COLORS.muted)
                setPadding(0, dp(5), 0, dp(10))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(compactButton("Use Shizuku", filled = executionMode == ExecutionMode.SHIZUKU) {
                    setExecutionMode(ExecutionMode.SHIZUKU)
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(compactButton("Use Wireless ADB", filled = executionMode == ExecutionMode.WIRELESS_ADB) {
                    setExecutionMode(ExecutionMode.WIRELESS_ADB)
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(10) })
            })
        }
    }

    private fun wirelessAdbPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(COLORS.surfaceAlt, dp(8), COLORS.outline, 1)

            addView(TextView(context).apply {
                text = "Wireless ADB"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
            })

            addView(TextView(context).apply {
                text = "Use Android 11+ Wireless debugging. Save the pairing code and port here; Shizulu will pair, find the connect service, and run modules over ADB."
                textSize = 13f
                setTextColor(COLORS.muted)
                setPadding(0, dp(5), 0, dp(10))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(compactButton("Open Settings", filled = false) { openDeveloperSettings() }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(compactButton("Configure", filled = true) { showWirelessAdbConfigDialog() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(10) })
                addView(compactButton("Test", filled = false) { testWirelessAdbBackend() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(10) })
            })
        }
    }

    private fun updaterPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(COLORS.surfaceAlt, dp(8), COLORS.outline, 1)

            addView(TextView(context).apply {
                text = "App updater"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
            })

            val savedRelease = savedLatestRelease()
            updaterStatusText = TextView(context).apply {
                text = visibleUpdaterStatus(savedRelease)
                textSize = 13f
                setTextColor(COLORS.muted)
                setPadding(0, dp(5), 0, dp(10))
            }
            addView(updaterStatusText)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(compactButton("Check", filled = false) { checkForUpdates() }, LinearLayout.LayoutParams(0, dp(42), 1f))
                updaterButton = compactButton("Update", filled = true) { installLatestUpdate() }.apply {
                    val enabled = savedRelease?.isNewerThanCurrent() == true
                    isEnabled = enabled
                    alpha = if (enabled) 1f else 0.45f
                }
                addView(updaterButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(10) })
            })
        }
    }

    private fun appearancePanel(): View {
        val mode = AppearanceMode.from(settingsPrefs.getString(KEY_APPEARANCE_MODE, null))
        val accent = AccentTheme.from(settingsPrefs.getString(KEY_ACCENT_THEME, null))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(COLORS.surfaceAlt, dp(8), COLORS.outline, 1)

            addView(TextView(context).apply {
                text = "Appearance"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
            })

            addView(TextView(context).apply {
                text = "Choose a light or dark manager theme and a Shizulu accent color."
                textSize = 13f
                setTextColor(COLORS.muted)
                setPadding(0, dp(5), 0, dp(10))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                lightModeButton = compactButton("Light", filled = mode == AppearanceMode.LIGHT) {
                    saveAppearance(AppearanceMode.LIGHT, accent)
                }
                darkModeButton = compactButton("Dark", filled = mode == AppearanceMode.DARK) {
                    saveAppearance(AppearanceMode.DARK, accent)
                }
                addView(lightModeButton, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(darkModeButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(10) })
            })

            addView(TextView(context).apply {
                text = "Theme"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.muted)
                setPadding(0, dp(12), 0, dp(8))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                AccentTheme.entries.forEachIndexed { index, theme ->
                    addView(themeSwatch(theme, theme == accent), LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        if (index > 0) leftMargin = dp(8)
                    })
                }
            })
        }
    }

    private fun themeSwatch(theme: AccentTheme, selected: Boolean): TextView {
        val palette = theme.palette(AppearanceMode.LIGHT)
        return TextView(this).apply {
            text = theme.label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else palette.primary)
            background = ripple(if (selected) palette.primary else COLORS.surface, if (selected) 0 else palette.primary)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                saveAppearance(AppearanceMode.from(settingsPrefs.getString(KEY_APPEARANCE_MODE, null)), theme)
            }
        }
    }

    private fun renderStatus() {
        mainHandler.post {
            val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val permissionGranted = binderAlive && hasShizukuPermission()
            val uid = service?.let { runCatching { it.uid }.getOrNull() }

            statusTitle.text = when {
                !binderAlive -> "Shizuku is not connected"
                !permissionGranted -> "Permission needed"
                uid == null -> "Ready to bind service"
                else -> "Shizulu is ready"
            }

            statusSubtitle.text = when {
                !binderAlive -> "Start Shizuku on the phone, then return here."
                !permissionGranted -> "Allow Shizulu so modules can use the shell identity."
                uid == null -> "Tap Grant Shizuku to connect the manager service."
                else -> "User service is bound as uid $uid. Shizules can run."
            }

            setChip(shizukuChip, if (binderAlive) "Shizuku running" else "Shizuku offline", binderAlive)
            setChip(permissionChip, if (permissionGranted) "Allowed" else "Needs grant", permissionGranted)
            setChip(serviceChip, if (uid != null) "Service bound" else "Service idle", uid != null)
            setChip(backendChip, executionMode.label, executionMode == ExecutionMode.SHIZUKU || wirelessAdbConfigured())

            grantButton.text = if (permissionGranted) "Bind service" else "Grant Shizuku"
        }
    }

    private fun renderDryRun() {
        if (::dryRunButton.isInitialized) {
            dryRunButton.text = if (dryRunEnabled) "Dry Run: On" else "Dry Run: Off"
            dryRunButton.setTextColor(if (dryRunEnabled) COLORS.warning else COLORS.primary)
        }
        if (::modeSummaryText.isInitialized) {
            modeSummaryText.text = "${if (dryRunEnabled) "Dry" else "Live"}\nMode"
            modeSummaryText.setTextColor(if (dryRunEnabled) COLORS.warning else COLORS.ink)
        }
    }

    private fun toggleDryRun() {
        dryRunEnabled = !dryRunEnabled
        settingsPrefs.edit().putBoolean(KEY_DRY_RUN, dryRunEnabled).apply()
        appendLog("Dry run ${if (dryRunEnabled) "enabled" else "disabled"}")
        renderDryRun()
        Toast.makeText(this, if (dryRunEnabled) "Dry run enabled" else "Dry run disabled", Toast.LENGTH_SHORT).show()
    }

    private fun refreshModules() {
        val shizules = store.list()
        if (::moduleCount.isInitialized) {
            moduleCount.text = shizules.size.toString()
        }
        if (::moduleSummaryText.isInitialized) {
            moduleSummaryText.text = "${shizules.size}\nModules"
        }
        if (::profilesList.isInitialized) {
            refreshProfiles(shizules)
        }
        if (::moduleList.isInitialized) {
            moduleList.removeAllViews()
            if (shizules.isEmpty()) {
                moduleList.addView(emptyState())
                return
            }
            shizules.forEachIndexed { index, shizule ->
                moduleList.addView(moduleView(shizule), spacedParams(top = if (index == 0) 0 else 12))
            }
        }
    }

    private fun refreshProfiles(shizules: List<Shizule>) {
        profilesList.removeAllViews()
        profilesList.addView(secondaryButton("Create Profile") { showCreateProfileDialog() }, LinearLayout.LayoutParams(-1, dp(46)))

        val profiles = PROFILES + loadCustomProfiles()
        if (::profileSummaryText.isInitialized) {
            profileSummaryText.text = "${profiles.size}\nProfiles"
        }
        profiles.forEachIndexed { index, profile ->
            profilesList.addView(profileView(profile, shizules), spacedParams(top = if (index == 0) 0 else 10))
        }
    }

    private fun profileView(profile: ShizuluProfile, shizules: List<Shizule>): View {
        val missing = profile.steps.count { step ->
            shizules.none { it.id == step.moduleId && it.actions.any { action -> action.id == step.actionId } }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedRect(COLORS.surface, dp(8), COLORS.outline, 1)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = profile.name
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(COLORS.ink)
                    })
                    addView(TextView(context).apply {
                        text = if (missing == 0) profile.description else "${profile.description} Missing $missing module/action."
                        textSize = 13f
                        setTextColor(if (missing == 0) COLORS.muted else COLORS.warning)
                        setPadding(0, dp(4), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(compactButton("Run", filled = missing == 0) { runProfile(profile) })
                    if (profile.custom) {
                        addView(compactButton("Remove", filled = false) {
                            deleteCustomProfile(profile)
                        }, rowGapParams())
                    }
                })
            })
        }
    }

    private fun showCreateProfileDialog() {
        val shizules = store.list()
        val available = shizules.flatMap { shizule ->
            shizule.actions.map { action ->
                ProfileChoice("${shizule.name} / ${action.label}", ProfileStep(shizule.id, action.id))
            }
        }
        if (available.isEmpty()) {
            Toast.makeText(this, "Install shizules before creating a profile.", Toast.LENGTH_LONG).show()
            return
        }

        val checked = BooleanArray(available.size)
        android.app.AlertDialog.Builder(this)
            .setTitle("Choose profile actions")
            .setMultiChoiceItems(available.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Next") { _, _ ->
                val steps = available.filterIndexed { index, _ -> checked[index] }.map { it.step }
                if (steps.isEmpty()) {
                    Toast.makeText(this, "Pick at least one action.", Toast.LENGTH_SHORT).show()
                } else {
                    showProfileNameDialog(steps)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProfileNameDialog(steps: List<ProfileStep>) {
        val input = EditText(this).apply {
            hint = "Profile name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Create profile")
            .setMessage("${steps.size} action(s) selected.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Custom Profile" }.take(48)
                val profile = ShizuluProfile(
                    name = name,
                    description = "${steps.size} custom action(s).",
                    steps = steps,
                    custom = true
                )
                saveCustomProfile(profile)
                appendLog("Created profile $name with ${steps.size} action(s)")
                refreshModules()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun emptyState(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(34), dp(22), dp(34))
            background = roundedRect(COLORS.surfaceAlt, dp(8), COLORS.outline, 1)

            addView(TextView(context).apply {
                text = "No shizules installed"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
                gravity = Gravity.CENTER
            })

            addView(TextView(context).apply {
                text = "Import a trusted .json file to add module actions here."
                textSize = 14f
                setTextColor(COLORS.muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun moduleView(shizule: Shizule): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(14))
            background = roundedRect(COLORS.surface, dp(8), COLORS.outline, 1)
            elevation = dp(1).toFloat()

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL

                    addView(TextView(context).apply {
                        text = shizule.name
                        textSize = 17f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(COLORS.ink)
                        includeFontPadding = false
                    })

                    addView(TextView(context).apply {
                        text = shizule.id
                        textSize = 12f
                        setTextColor(COLORS.muted)
                        setPadding(0, dp(5), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))

                addView(TextView(context).apply {
                    text = shizule.version
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLORS.primary)
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background = roundedRect(COLORS.primarySoft, dp(14))
                })
            })

            addView(TextView(context).apply {
                text = shizule.description.ifBlank { "No description provided." }
                textSize = 14f
                setTextColor(COLORS.body)
                setLineSpacing(0f, 1.08f)
                setPadding(0, dp(12), 0, dp(13))
            })

            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    shizule.actions.forEach { action ->
                        addView(compactButton(action.label, filled = true) { runAction(shizule, action) })
                    }
                    addView(compactButton("Remove", filled = false) {
                        store.delete(shizule)
                        refreshModules()
                    }, rowGapParams())
                })
            })
        }
    }

    private fun openJsonPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/json", "text/plain"))
            },
            REQUEST_IMPORT
        )
    }

    private fun installFromUri(uri: Uri) {
        val raw = contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: return

        runCatching { Shizule.fromJson(raw) }
            .onSuccess { shizule ->
                installTrusted(raw, shizule)
            }
            .onFailure {
                appendLog("Install failed: ${it.message ?: "invalid shizule"}")
                Toast.makeText(this, it.message ?: "Invalid shizule", Toast.LENGTH_LONG).show()
            }
    }

    private fun installTrusted(raw: String, shizule: Shizule) {
        runCatching { store.install(raw) }
            .onSuccess {
                appendLog("Installed shizule ${it.name} (${it.id})")
                Toast.makeText(this, "Installed ${it.name}", Toast.LENGTH_SHORT).show()
                refreshModules()
            }
            .onFailure {
                appendLog("Install failed: ${it.message ?: "invalid shizule"}")
                Toast.makeText(this, it.message ?: "Invalid shizule", Toast.LENGTH_LONG).show()
            }
    }

    private fun requestShizukuPermission() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            appendLog("Shizuku permission request failed: Shizuku is not connected")
            Toast.makeText(this, "Start Shizuku first.", Toast.LENGTH_LONG).show()
            renderStatus()
            return
        }
        if (!hasShizukuPermission()) Shizuku.requestPermission(REQUEST_SHIZUKU)
        else bindUserService()
    }

    private fun hasShizukuPermission(): Boolean {
        return runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun bindUserService() {
        if (service != null || !hasShizukuPermission()) return
        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, ShizuluUserService::class.java.name)
        )
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
            .processNameSuffix("service")
            .version(1)
        Shizuku.bindUserService(args, serviceConnection)
    }

    private fun setExecutionMode(mode: ExecutionMode) {
        executionMode = mode
        settingsPrefs.edit().putString(KEY_EXECUTION_MODE, mode.name).apply()
        appendLog("Execution backend set to ${mode.label}")
        renderStatus()
        if (currentPage == Page.TOOLS) showPage(Page.TOOLS)
    }

    private fun saveAppearance(mode: AppearanceMode, accent: AccentTheme) {
        settingsPrefs.edit()
            .putString(KEY_APPEARANCE_MODE, mode.name)
            .putString(KEY_ACCENT_THEME, accent.name)
            .apply()
        applyThemeFromPrefs()
        appendLog("Appearance set to ${mode.label} / ${accent.label}")
        buildUi()
    }

    private fun applyThemeFromPrefs() {
        val mode = AppearanceMode.from(settingsPrefs.getString(KEY_APPEARANCE_MODE, null))
        val accent = AccentTheme.from(settingsPrefs.getString(KEY_ACCENT_THEME, null))
        COLORS.palette = accent.palette(mode)
    }

    private fun wirelessAdbConfigured(): Boolean {
        return settingsPrefs.getString(KEY_ADB_PAIRING_CODE, "").orEmpty().isNotBlank() &&
            settingsPrefs.getInt(KEY_ADB_PAIR_PORT, 0) > 0
    }

    private fun openDeveloperSettings() {
        val devSettings = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val settings = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            startActivity(devSettings)
        }.onFailure {
            startActivity(settings)
        }
    }

    private fun showWirelessAdbConfigDialog() {
        val currentCode = settingsPrefs.getString(KEY_ADB_PAIRING_CODE, "").orEmpty()
        val currentPort = settingsPrefs.getInt(KEY_ADB_PAIR_PORT, 0).takeIf { it > 0 }?.toString().orEmpty()
        val codeInput = EditText(this).apply {
            hint = "Pairing code"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentCode)
        }
        val portInput = EditText(this).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentPort)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(codeInput)
            addView(portInput)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Wireless ADB setup")
            .setMessage("Enter the pairing code and port shown by Android's Wireless debugging pairing dialog.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val pairingCode = codeInput.text.toString().trim()
                val port = portInput.text.toString().trim().toIntOrNull() ?: 0
                settingsPrefs.edit()
                    .putString(KEY_ADB_PAIRING_CODE, pairingCode)
                    .putInt(KEY_ADB_PAIR_PORT, port)
                    .apply()
                appendLog("Wireless ADB config saved: code=${pairingCode.maskPairingCode()} port=$port")
                renderStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testWirelessAdbBackend() {
        val pairingCode = settingsPrefs.getString(KEY_ADB_PAIRING_CODE, "").orEmpty()
        val port = settingsPrefs.getInt(KEY_ADB_PAIR_PORT, 0)
        if (pairingCode.isBlank() || port <= 0) {
            appendLog("Wireless ADB test: not configured")
            showOutput(
                "Wireless ADB",
                "Pairing required.\n\nOpen Android Settings > Developer options > Wireless debugging, choose Pair device with pairing code, then save that pairing code and port in Shizulu.\n\nStandalone Wireless ADB execution is included in this build; it just needs a fresh pairing first."
            )
            return
        }

        appendLog("Wireless ADB test started: code=${pairingCode.maskPairingCode()} port=$port")
        Toast.makeText(this, "Testing Wireless ADB...", Toast.LENGTH_SHORT).show()
        executor.execute {
            runCatching {
                WirelessAdbRunner(applicationContext).test(pairingCode, port)
            }
                .onSuccess { result ->
                    appendLog("Wireless ADB test succeeded")
                    mainHandler.post { showOutput("Wireless ADB", result.output) }
                }
                .onFailure {
                    val message = it.message ?: it.javaClass.simpleName
                    appendLog("Wireless ADB test failed: $message")
                    mainHandler.post { showOutput("Wireless ADB", "Failed: $message") }
                }
        }
    }

    private fun runAction(shizule: Shizule, action: ShizuleAction) {
        if (dryRunEnabled) {
            dryRunAction(shizule, action)
            return
        }

        if (executionMode == ExecutionMode.WIRELESS_ADB) {
            runWirelessAdbAction(shizule, action)
            return
        }

        if (!hasShizukuPermission()) {
            requestShizukuPermission()
            return
        }
        bindUserService()
        val currentService = service
        if (currentService == null) {
            appendLog("Action ${shizule.name}/${action.label} delayed: service is still binding")
            Toast.makeText(this, "Binding Shizulu service. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        executor.execute {
            val output = StringBuilder()
            var failed = false
            appendLog("Started ${shizule.name}/${action.label} (${action.commands.size} command(s))")
            runCatching {
                action.commands.forEachIndexed { index, command ->
                    output.append("$ ").append(command.exec).append('\n')
                    val result = currentService.runShizuleCommand(shizule.id, command.exec)
                    if (!result.startsWith("exit=0")) failed = true
                    output.append(result).append("\n\n")
                    appendLog("${shizule.name}/${action.label} command ${index + 1}: ${result.lineSequence().firstOrNull() ?: "no exit"}")
                    mainHandler.post {
                        Toast.makeText(this, "Ran ${index + 1}/${action.commands.size} for ${shizule.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
                .onSuccess {
                    appendLog("${if (failed) "Finished with failures" else "Finished successfully"}: ${shizule.name}/${action.label}")
                    mainHandler.post { showOutput(shizule.name, output.toString()) }
                }
                .onFailure {
                    appendLog("Failed ${shizule.name}/${action.label}: ${it.message ?: it.javaClass.simpleName}")
                    mainHandler.post { showOutput(shizule.name, "Failed: ${it.message ?: it.javaClass.simpleName}\n\n$output") }
                }
        }
    }

    private fun runWirelessAdbAction(shizule: Shizule, action: ShizuleAction) {
        val pairingCode = settingsPrefs.getString(KEY_ADB_PAIRING_CODE, "").orEmpty()
        val port = settingsPrefs.getInt(KEY_ADB_PAIR_PORT, 0)
        if (pairingCode.isBlank() || port <= 0) {
            appendLog("Wireless ADB blocked ${shizule.name}/${action.label}: missing pairing code or port")
            showOutput(
                "${shizule.name} Wireless ADB",
                "Pairing required.\n\nOpen Tools > Wireless ADB > Configure and enter the fresh pairing code plus port from Android's Wireless debugging pairing dialog."
            )
            return
        }

        appendLog("Wireless ADB started ${shizule.name}/${action.label} (${action.commands.size} command(s))")
        executor.execute {
            runCatching {
                WirelessAdbRunner(applicationContext).run(shizule.id, action, pairingCode, port)
            }
                .onSuccess { result ->
                    val failed = result.output.lineSequence().any { it.startsWith("exit=") && it != "exit=0" }
                    appendLog("${if (failed) "Wireless ADB finished with failures" else "Wireless ADB finished successfully"}: ${shizule.name}/${action.label}")
                    mainHandler.post {
                        Toast.makeText(this, "Wireless ADB ran ${action.commands.size} command(s)", Toast.LENGTH_SHORT).show()
                        showOutput("${shizule.name} Wireless ADB", result.output)
                    }
                }
                .onFailure {
                    val message = it.message ?: it.javaClass.simpleName
                    appendLog("Wireless ADB failed ${shizule.name}/${action.label}: $message")
                    mainHandler.post { showOutput("${shizule.name} Wireless ADB", "Failed: $message") }
                }
        }
    }

    private fun String.maskPairingCode(): String {
        return when {
            length <= 2 -> "**"
            else -> "*".repeat(length - 2) + takeLast(2)
        }
    }

    private fun dryRunAction(shizule: Shizule, action: ShizuleAction) {
        val preview = buildString {
            append("Dry run: no commands executed.\n\n")
            action.commands.forEachIndexed { index, command ->
                append(index + 1).append(". ").append(command.exec).append('\n')
                appendLog("Dry run ${shizule.name}/${action.label} command ${index + 1}: ${command.exec}")
            }
        }
        appendLog("Dry run finished: ${shizule.name}/${action.label}")
        showOutput("${shizule.name} dry run", preview)
    }

    private fun runProfile(profile: ShizuluProfile) {
        val shizules = store.list()
        val missing = profile.steps.filter { step ->
            shizules.none { it.id == step.moduleId && it.actions.any { action -> action.id == step.actionId } }
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "Install missing shizules first.", Toast.LENGTH_LONG).show()
            appendLog("Profile ${profile.name} blocked: ${missing.size} missing step(s)")
            return
        }

        appendLog("Started profile ${profile.name}")
        profile.steps.forEach { step ->
            val shizule = shizules.first { it.id == step.moduleId }
            val action = shizule.actions.first { it.id == step.actionId }
            runAction(shizule, action)
        }
    }

    private fun loadCustomProfiles(): List<ShizuluProfile> {
        val raw = settingsPrefs.getString(KEY_CUSTOM_PROFILES, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    val stepsArray = obj.getJSONArray("steps")
                    val steps = buildList {
                        for (stepIndex in 0 until stepsArray.length()) {
                            val step = stepsArray.getJSONObject(stepIndex)
                            add(ProfileStep(step.getString("moduleId"), step.getString("actionId")))
                        }
                    }
                    add(
                        ShizuluProfile(
                            name = obj.optString("name", "Custom Profile").take(48),
                            description = obj.optString("description", "${steps.size} custom action(s).").take(120),
                            steps = steps,
                            custom = true
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveCustomProfile(profile: ShizuluProfile) {
        val profiles = loadCustomProfiles() + profile
        settingsPrefs.edit().putString(KEY_CUSTOM_PROFILES, profilesToJson(profiles).toString()).apply()
    }

    private fun deleteCustomProfile(profile: ShizuluProfile) {
        val remaining = loadCustomProfiles().filterNot { it.name == profile.name && it.steps == profile.steps }
        settingsPrefs.edit().putString(KEY_CUSTOM_PROFILES, profilesToJson(remaining).toString()).apply()
        appendLog("Deleted profile ${profile.name}")
        refreshModules()
    }

    private fun profilesToJson(profiles: List<ShizuluProfile>): JSONArray {
        return JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().apply {
                    put("name", profile.name)
                    put("description", profile.description)
                    put("steps", JSONArray().apply {
                        profile.steps.forEach { step ->
                            put(JSONObject().apply {
                                put("moduleId", step.moduleId)
                                put("actionId", step.actionId)
                            })
                        }
                    })
                })
            }
        }
    }

    private fun openBackupCreator() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "shizulu-backup-$stamp.json")
            },
            REQUEST_BACKUP_CREATE
        )
    }

    private fun openBackupPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            },
            REQUEST_BACKUP_OPEN
        )
    }

    private fun writeBackupToUri(uri: Uri) {
        val backup = JSONObject().apply {
            put("schema", 1)
            put("createdAt", timestamp())
            put("dryRun", dryRunEnabled)
            put("appearanceMode", AppearanceMode.from(settingsPrefs.getString(KEY_APPEARANCE_MODE, null)).name)
            put("accentTheme", AccentTheme.from(settingsPrefs.getString(KEY_ACCENT_THEME, null)).name)
            put("logs", readLogs())
            put("customProfiles", profilesToJson(loadCustomProfiles()))
            put("shizules", JSONArray().apply {
                store.listRaw().forEach { raw ->
                    runCatching { put(JSONObject(raw)) }
                }
            })
        }

        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(backup.toString(2).toByteArray(Charsets.UTF_8))
            } ?: error("Could not open backup file.")
        }
            .onSuccess {
                appendLog("Backup exported")
                Toast.makeText(this, "Backup exported", Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                appendLog("Backup failed: ${it.message ?: it.javaClass.simpleName}")
                Toast.makeText(this, "Backup failed", Toast.LENGTH_LONG).show()
            }
    }

    private fun restoreBackupFromUri(uri: Uri) {
        val raw = contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: return

        runCatching {
            val backup = JSONObject(raw)
            require(backup.optInt("schema", 0) == 1) { "Unsupported backup schema." }

            val shizules = backup.optJSONArray("shizules") ?: JSONArray()
            val rawShizules = buildList {
                for (index in 0 until shizules.length()) add(shizules.getJSONObject(index).toString())
            }
            val installed = store.installAll(rawShizules)

            val customProfiles = backup.optJSONArray("customProfiles") ?: JSONArray()
            settingsPrefs.edit()
                .putBoolean(KEY_DRY_RUN, backup.optBoolean("dryRun", dryRunEnabled))
                .putString(KEY_CUSTOM_PROFILES, customProfiles.toString())
                .putString(KEY_APPEARANCE_MODE, backup.optString("appearanceMode", AppearanceMode.LIGHT.name))
                .putString(KEY_ACCENT_THEME, backup.optString("accentTheme", AccentTheme.BLUE.name))
                .apply()
            dryRunEnabled = settingsPrefs.getBoolean(KEY_DRY_RUN, false)
            applyThemeFromPrefs()

            val restoredLogs = backup.optString("logs", "")
            if (restoredLogs.isNotBlank()) logPrefs.edit().putString(KEY_LOGS, restoredLogs).apply()
            appendLog("Backup restored: $installed shizule(s), ${customProfiles.length()} custom profile(s)")
            buildUi()
            renderDryRun()
            refreshModules()
            Toast.makeText(this, "Backup restored", Toast.LENGTH_SHORT).show()
        }
            .onFailure {
                appendLog("Restore failed: ${it.message ?: it.javaClass.simpleName}")
                Toast.makeText(this, it.message ?: "Restore failed", Toast.LENGTH_LONG).show()
            }
    }

    private fun checkForUpdates() {
        if (!::updaterStatusText.isInitialized) return
        latestRelease = null
        settingsPrefs.edit()
            .remove(KEY_LATEST_RELEASE)
            .putString(KEY_UPDATER_STATUS, "Checking GitHub releases...")
            .apply()
        setUpdaterButton(false)
        updaterStatusText.text = "Checking GitHub releases..."
        appendLog("Update check started")

        executor.execute {
            runCatching { fetchLatestRelease() }
                .onSuccess { release ->
                    latestRelease = release
                    val updateAvailable = release.isNewerThanCurrent()
                    appendLog("Update check ${if (updateAvailable) "found ${release.tag}" else "current"}")
                    mainHandler.post {
                        if (updateAvailable) {
                            val status = "Update available: ${release.tag}\nCurrent: ${BuildConfig.GIT_SHA}\nLatest: ${release.commitSha ?: "unknown commit"}"
                            updaterStatusText.text = status
                            saveUpdaterState(status, release)
                            setUpdaterButton(true)
                        } else {
                            val status = "You're up to date.\nCurrent build: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"
                            updaterStatusText.text = status
                            saveUpdaterState(status, null)
                            setUpdaterButton(false)
                        }
                    }
                }
                .onFailure {
                    val message = it.message ?: it.javaClass.simpleName
                    appendLog("Update check failed: $message")
                    mainHandler.post {
                        val status = "Update check failed: $message"
                        updaterStatusText.text = status
                        saveUpdaterState(status, savedLatestRelease())
                        setUpdaterButton(false)
                    }
                }
        }
    }

    private fun installLatestUpdate() {
        val release = latestRelease ?: savedLatestRelease()
        if (release == null || !release.isNewerThanCurrent()) {
            Toast.makeText(this, "Check for updates first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            appendLog("Update install blocked: install unknown apps permission needed")
            Toast.makeText(this, "Allow Shizulu to install updates, then tap Update again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        val status = "Downloading ${release.tag}..."
        updaterStatusText.text = status
        saveUpdaterState(status, release)
        setUpdaterButton(false)
        appendLog("Update download started: ${release.tag}")

        executor.execute {
            runCatching {
                val apk = downloadUpdateApk(release)
                mainHandler.post { launchApkInstaller(apk, release) }
            }
                .onFailure {
                    val message = it.message ?: it.javaClass.simpleName
                    appendLog("Update download failed: $message")
                    mainHandler.post {
                        val failedStatus = "Update download failed: $message"
                        updaterStatusText.text = failedStatus
                        saveUpdaterState(failedStatus, release)
                        setUpdaterButton(true)
                    }
                }
        }
    }

    private fun fetchLatestRelease(): GithubRelease {
        val json = httpGet(GITHUB_LATEST_RELEASE_URL)
        val obj = JSONObject(json)
        val assets = obj.optJSONArray("assets") ?: JSONArray()
        var apkUrl = ""
        var apkName = ""
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                apkName = name
                apkUrl = url
                break
            }
        }
        require(apkUrl.isNotBlank()) { "Latest release has no APK asset." }
        val body = obj.optString("body")
        return GithubRelease(
            tag = obj.optString("tag_name", "latest"),
            name = obj.optString("name", "Latest release"),
            commitSha = body.lineSequence()
                .firstOrNull { it.trim().startsWith("Commit:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            apkName = apkName,
            apkUrl = apkUrl
        )
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Shizulu/${BuildConfig.VERSION_NAME}")
        }
        return connection.useResponse { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun downloadUpdateApk(release: GithubRelease): File {
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val apk = File(updatesDir, release.apkName.ifBlank { "Shizulu-${release.tag}.apk" })
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Shizulu/${BuildConfig.VERSION_NAME}")
        }
        connection.useResponse { input ->
            apk.outputStream().use { output -> input.copyTo(output) }
        }
        require(apk.length() > 0L) { "Downloaded APK is empty." }
        appendLog("Update downloaded: ${apk.name} (${apk.length()} bytes)")
        return apk
    }

    private fun launchApkInstaller(apk: File, release: GithubRelease) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appendLog("Launching updater installer: ${release.tag}")
        val status = "Downloaded ${release.tag}. Finish the Android installer to update."
        updaterStatusText.text = status
        saveUpdaterState(status, release)
        startActivity(intent)
    }

    private fun setUpdaterButton(enabled: Boolean) {
        if (!::updaterButton.isInitialized) return
        updaterButton.isEnabled = enabled
        updaterButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun saveUpdaterState(status: String, release: GithubRelease?) {
        settingsPrefs.edit()
            .putString(KEY_UPDATER_STATUS, status)
            .apply {
                if (release == null) remove(KEY_LATEST_RELEASE) else putString(KEY_LATEST_RELEASE, release.toJson().toString())
            }
            .apply()
    }

    private fun savedUpdaterStatus(): String? {
        return settingsPrefs.getString(KEY_UPDATER_STATUS, null)?.takeIf { it.isNotBlank() }
    }

    private fun visibleUpdaterStatus(release: GithubRelease?): String {
        if (release != null && !release.isNewerThanCurrent()) {
            val status = "You're up to date.\nCurrent build: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"
            saveUpdaterState(status, null)
            return status
        }
        return savedUpdaterStatus() ?: "Current build: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"
    }

    private fun savedLatestRelease(): GithubRelease? {
        if (latestRelease != null) return latestRelease
        val raw = settingsPrefs.getString(KEY_LATEST_RELEASE, null) ?: return null
        return runCatching { GithubRelease.fromJson(JSONObject(raw)) }.getOrNull()?.also { latestRelease = it }
    }

    private fun <T> HttpURLConnection.useResponse(block: (java.io.InputStream) -> T): T {
        return try {
            val code = responseCode
            val stream = if (code in 200..299) inputStream else errorStream
            val body = stream.use(block)
            if (code !in 200..299) error("HTTP $code")
            body
        } finally {
            disconnect()
        }
    }

    private fun GithubRelease.isNewerThanCurrent(): Boolean {
        val latest = commitSha ?: return tag != BuildConfig.VERSION_NAME
        val current = BuildConfig.GIT_SHA
        return !latest.startsWith(current, ignoreCase = true) && !current.startsWith(latest, ignoreCase = true)
    }

    private fun showOutput(title: String, output: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(output.ifBlank { "Done." })
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLogs() {
        val logs = readLogs().ifBlank { "No logs yet." }
        android.app.AlertDialog.Builder(this)
            .setTitle("Shizulu logs")
            .setMessage(logs)
            .setPositiveButton("OK", null)
            .setNegativeButton("Clear") { _, _ -> clearLogs() }
            .show()
    }

    private fun appendLog(message: String) {
        val current = readLogs()
        val next = buildString {
            if (current.isNotBlank()) append(current).append('\n')
            append(timestamp()).append("  ").append(message)
        }.lines().takeLast(MAX_LOG_LINES).joinToString("\n")
        logPrefs.edit().putString(KEY_LOGS, next).apply()
    }

    private fun readLogs(): String = logPrefs.getString(KEY_LOGS, "").orEmpty()

    private fun clearLogs() {
        logPrefs.edit().remove(KEY_LOGS).apply()
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
    }

    private fun timestamp(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView {
        return textButton(label, COLORS.primary, Color.WHITE, 0, onClick)
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): TextView {
        return textButton(label, COLORS.surface, COLORS.primary, COLORS.primary, onClick)
    }

    private fun compactButton(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val background = if (filled) COLORS.primary else COLORS.surfaceAlt
        val foreground = if (filled) Color.WHITE else COLORS.ink
        val stroke = if (filled) 0 else COLORS.outlineStrong
        return textButton(label, background, foreground, stroke, onClick).apply {
            textSize = 13f
            minWidth = dp(88)
            minHeight = dp(38)
            setPadding(dp(14), 0, dp(14), 0)
        }
    }

    private fun textButton(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        strokeColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(textColor)
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
            setPadding(dp(14), 0, dp(14), 0)
            background = ripple(backgroundColor, strokeColor)
            setOnClickListener { onClick() }
        }
    }

    private fun chip(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
    }

    private fun setChip(chip: TextView, label: String, positive: Boolean) {
        val bg = if (positive) COLORS.successSoft else COLORS.warningSoft
        val fg = if (positive) COLORS.success else COLORS.warning
        chip.text = label
        chip.setTextColor(fg)
        chip.background = roundedRect(bg, dp(16))
    }

    private fun ripple(backgroundColor: Int, strokeColor: Int = 0): RippleDrawable {
        val shape = roundedRect(backgroundColor, dp(8), strokeColor, if (strokeColor == 0) 0 else 1)
        return RippleDrawable(ColorStateList.valueOf(0x223A7DFF), shape, null)
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }
    }

    private fun spacedParams(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    }

    private fun rowGapParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private object COLORS {
        var palette = AccentTheme.BLUE.palette(AppearanceMode.LIGHT)
        val dark get() = palette.dark
        val background get() = palette.background
        val surface get() = palette.surface
        val surfaceAlt get() = palette.surfaceAlt
        val ink get() = palette.ink
        val body get() = palette.body
        val muted get() = palette.muted
        val outline get() = palette.outline
        val outlineStrong get() = palette.outlineStrong
        val primary get() = palette.primary
        val primarySoft get() = palette.primarySoft
        val secondary get() = palette.secondary
        val secondarySoft get() = palette.secondarySoft
        val success get() = palette.success
        val successSoft get() = palette.successSoft
        val warning get() = palette.warning
        val warningSoft get() = palette.warningSoft
    }

    companion object {
        private const val REQUEST_IMPORT = 42
        private const val REQUEST_SHIZUKU = 43
        private const val REQUEST_BACKUP_CREATE = 44
        private const val REQUEST_BACKUP_OPEN = 45
        private const val KEY_LOGS = "logs"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_CUSTOM_PROFILES = "custom_profiles"
        private const val KEY_EXECUTION_MODE = "execution_mode"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
        private const val KEY_ACCENT_THEME = "accent_theme"
        private const val KEY_UPDATER_STATUS = "updater_status"
        private const val KEY_LATEST_RELEASE = "latest_release"
        private const val KEY_ADB_PAIRING_CODE = "adb_pairing_code"
        private const val KEY_ADB_PAIR_PORT = "adb_pair_port"
        private const val MAX_LOG_LINES = 160
        private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/Glitch98777/shizulu/releases/latest"

        private val PROFILES = listOf(
            ShizuluProfile(
                name = "Comfort Setup",
                description = "Fast animations plus display comfort.",
                steps = listOf(
                    ProfileStep("com.shizulu.sample.animation_tuner", "fast"),
                    ProfileStep("com.shizulu.sample.display_comfort_pack", "comfort")
                ),
                custom = false
            ),
            ShizuluProfile(
                name = "Clean Pixel",
                description = "Light debloat and fast animations.",
                steps = listOf(
                    ProfileStep("com.shizulu.sample.light_debloat_manager", "apply"),
                    ProfileStep("com.shizulu.sample.animation_tuner", "fast")
                ),
                custom = false
            ),
            ShizuluProfile(
                name = "Stock Restore",
                description = "Restore defaults for bundled shizules.",
                steps = listOf(
                    ProfileStep("com.shizulu.sample.light_debloat_manager", "restore"),
                    ProfileStep("com.shizulu.sample.display_comfort_pack", "restore"),
                    ProfileStep("com.shizulu.sample.animation_tuner", "normal")
                ),
                custom = false
            )
        )
    }
}

data class ShizuluProfile(
    val name: String,
    val description: String,
    val steps: List<ProfileStep>,
    val custom: Boolean
)

data class ProfileStep(
    val moduleId: String,
    val actionId: String
)

data class ProfileChoice(
    val label: String,
    val step: ProfileStep
)

data class GithubRelease(
    val tag: String,
    val name: String,
    val commitSha: String?,
    val apkName: String,
    val apkUrl: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("tag", tag)
            put("name", name)
            put("commitSha", commitSha ?: "")
            put("apkName", apkName)
            put("apkUrl", apkUrl)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): GithubRelease {
            return GithubRelease(
                tag = obj.optString("tag"),
                name = obj.optString("name"),
                commitSha = obj.optString("commitSha").takeIf { it.isNotBlank() },
                apkName = obj.optString("apkName"),
                apkUrl = obj.optString("apkUrl")
            )
        }
    }
}

data class ThemePalette(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val ink: Int,
    val body: Int,
    val muted: Int,
    val outline: Int,
    val outlineStrong: Int,
    val primary: Int,
    val primarySoft: Int,
    val secondary: Int,
    val secondarySoft: Int,
    val success: Int,
    val successSoft: Int,
    val warning: Int,
    val warningSoft: Int
)

enum class Page {
    HOME,
    MODULES,
    PROFILES,
    TOOLS
}

enum class ExecutionMode(val label: String) {
    SHIZUKU("Shizuku"),
    WIRELESS_ADB("Wireless ADB");

    companion object {
        fun from(value: String?): ExecutionMode {
            return entries.firstOrNull { it.name == value } ?: SHIZUKU
        }
    }
}

enum class AppearanceMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun from(value: String?): AppearanceMode {
            return entries.firstOrNull { it.name == value } ?: LIGHT
        }
    }
}

enum class AccentTheme(val label: String, private val lightPrimary: Int, private val darkPrimary: Int) {
    BLUE("Blue", 0xFF3A7DFF.toInt(), 0xFF7BA7FF.toInt()),
    JADE("Jade", 0xFF0F8B6F.toInt(), 0xFF4DD6B3.toInt()),
    VIOLET("Violet", 0xFF7C3AED.toInt(), 0xFFA78BFA.toInt()),
    ROSE("Rose", 0xFFE0446D.toInt(), 0xFFFF7A9B.toInt());

    fun palette(mode: AppearanceMode): ThemePalette {
        val dark = mode == AppearanceMode.DARK
        val primary = if (dark) darkPrimary else lightPrimary
        return ThemePalette(
            dark = dark,
            background = if (dark) 0xFF0D111A.toInt() else 0xFFF7F9FC.toInt(),
            surface = if (dark) 0xFF151B26.toInt() else 0xFFFFFFFF.toInt(),
            surfaceAlt = if (dark) 0xFF1C2431.toInt() else 0xFFF1F5F9.toInt(),
            ink = if (dark) 0xFFF4F7FB.toInt() else 0xFF172033.toInt(),
            body = if (dark) 0xFFD7DEE8.toInt() else 0xFF31415A.toInt(),
            muted = if (dark) 0xFF9CA8B8.toInt() else 0xFF6B778C.toInt(),
            outline = if (dark) 0xFF2A3443.toInt() else 0xFFE1E8F0.toInt(),
            outlineStrong = if (dark) 0xFF3C485B.toInt() else 0xFFCBD5E1.toInt(),
            primary = primary,
            primarySoft = if (dark) tint(primary, 0.18f, 0xFF0D111A.toInt()) else tint(primary, 0.12f, 0xFFFFFFFF.toInt()),
            secondary = if (dark) 0xFFC4B5FD.toInt() else 0xFF7C3AED.toInt(),
            secondarySoft = if (dark) 0xFF2D2547.toInt() else 0xFFF1EAFF.toInt(),
            success = if (dark) 0xFF6EE7B7.toInt() else 0xFF0E7A53.toInt(),
            successSoft = if (dark) 0xFF12392E.toInt() else 0xFFE4F8EF.toInt(),
            warning = if (dark) 0xFFFDBA74.toInt() else 0xFF9A5B00.toInt(),
            warningSoft = if (dark) 0xFF3B2813.toInt() else 0xFFFFF1D6.toInt()
        )
    }

    companion object {
        fun from(value: String?): AccentTheme {
            return entries.firstOrNull { it.name == value } ?: BLUE
        }

        private fun tint(color: Int, amount: Float, base: Int): Int {
            val r = Color.red(base) + ((Color.red(color) - Color.red(base)) * amount).toInt()
            val g = Color.green(base) + ((Color.green(color) - Color.green(base)) * amount).toInt()
            val b = Color.blue(base) + ((Color.blue(color) - Color.blue(base)) * amount).toInt()
            return Color.rgb(r, g, b)
        }
    }
}

class NavIconView(
    context: android.content.Context,
    private val page: Page
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF6B778C.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF6B778C.toInt()
    }
    private val rect = RectF()

    fun setIconColor(color: Int) {
        paint.color = color
        fillPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        when (page) {
            Page.HOME -> drawHome(canvas, w, h)
            Page.MODULES -> drawModules(canvas, w, h)
            Page.PROFILES -> drawProfiles(canvas, w, h)
            Page.TOOLS -> drawTools(canvas, w, h)
        }
    }

    private fun drawHome(canvas: Canvas, w: Float, h: Float) {
        val left = w * 0.22f
        val right = w * 0.78f
        val top = h * 0.42f
        val bottom = h * 0.84f
        canvas.drawLine(w * 0.16f, top, w * 0.5f, h * 0.14f, paint)
        canvas.drawLine(w * 0.5f, h * 0.14f, w * 0.84f, top, paint)
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, 3.5f, 3.5f, paint)
    }

    private fun drawModules(canvas: Canvas, w: Float, h: Float) {
        val size = w * 0.24f
        val gap = w * 0.13f
        val startX = (w - size * 2f - gap) / 2f
        val startY = h * 0.18f
        repeat(2) { row ->
            repeat(2) { col ->
                val x = startX + col * (size + gap)
                val y = startY + row * (size + gap * 0.72f)
                rect.set(x, y, x + size, y + size)
                canvas.drawRoundRect(rect, 4f, 4f, paint)
            }
        }
    }

    private fun drawProfiles(canvas: Canvas, w: Float, h: Float) {
        val left = w * 0.20f
        val right = w * 0.80f
        val height = h * 0.18f
        val top = h * 0.22f
        repeat(3) { index ->
            val y = top + index * h * 0.20f
            val inset = index * w * 0.045f
            rect.set(left + inset, y, right - inset, y + height)
            canvas.drawRoundRect(rect, 5f, 5f, paint)
        }
    }

    private fun drawTools(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.50f
        canvas.drawCircle(cx, cy, w * 0.18f, paint)
        canvas.drawCircle(cx, cy, w * 0.05f, fillPaint)
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val inner = w * 0.25f
            val outer = w * 0.34f
            val x1 = cx + kotlin.math.cos(angle).toFloat() * inner
            val y1 = cy + kotlin.math.sin(angle).toFloat() * inner
            val x2 = cx + kotlin.math.cos(angle).toFloat() * outer
            val y2 = cy + kotlin.math.sin(angle).toFloat() * outer
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }
}
