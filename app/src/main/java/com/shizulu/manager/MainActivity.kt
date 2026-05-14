package com.shizulu.manager

import android.Manifest
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private lateinit var keepAliveButton: TextView
    private lateinit var batteryButton: TextView
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

    override fun onResume() {
        super.onResume()
        if (currentPage == Page.TOOLS && ::contentHost.isInitialized) showPage(Page.TOOLS)
    }

    @Deprecated("Kept for the platform document picker callback.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK) {
            data?.let(::installFromPickerResult)
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
        val targetPage = currentPage
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
        showPage(targetPage)
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
                content.addView(executionModePanel(), spacedParams(top = 10))
                content.addView(wirelessAdbPanel(), spacedParams(top = 10))
                content.addView(shizuleLabPanel(), spacedParams(top = 10))
                content.addView(rootlessPowerPanel(), spacedParams(top = 10))
                content.addView(persistencePanel(), spacedParams(top = 10))
                content.addView(updaterPanel(), spacedParams(top = 10))
                content.addView(appearancePanel(), spacedParams(top = 10))
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
        val color = if (selected) COLORS.ink else COLORS.muted
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
                setTextColor(COLORS.onPrimary)
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(COLORS.surface, dp(8), COLORS.outline, 1)
            elevation = dp(1).toFloat()

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL

                statusTitle = TextView(context).apply {
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLORS.ink)
                    includeFontPadding = false
                }
                addView(statusTitle)

                statusSubtitle = TextView(context).apply {
                    textSize = 12f
                    setTextColor(COLORS.muted)
                    includeFontPadding = false
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                }
                addView(statusSubtitle)
            }, LinearLayout.LayoutParams(0, -2, 1f))

            backendChip = chip("Backend").apply {
                minWidth = dp(92)
                setPadding(dp(12), dp(7), dp(12), dp(7))
            }
            addView(backendChip, LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(10) })
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

                grantButton = readableSecondaryButton("Grant Shizuku") {
                    if (executionMode == ExecutionMode.WIRELESS_ADB) showPage(Page.TOOLS) else requestShizukuPermission()
                }
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
                setTextColor(COLORS.ink)
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

            addView(readableSecondaryButton("Logs") { showLogs() }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(readableSecondaryButton("Backup") { openBackupCreator() }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(readableSecondaryButton("Restore Backup") { openBackupPicker() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
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
            addView(compactButton("Auto Repair", filled = false) { autoRepairWirelessAdbBackend() }, spacedParams(top = 10))
        }
    }

    private fun persistencePanel(): View {
        val keepAliveEnabled = settingsPrefs.getBoolean(KEY_WIRELESS_ADB_KEEP_ALIVE, false)
        val batteryIgnored = batteryOptimizationIgnored()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(COLORS.surfaceAlt, dp(8), COLORS.outline, 1)

            addView(TextView(context).apply {
                text = "Persistent ADB"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
            })

            addView(TextView(context).apply {
                text = if (keepAliveEnabled) {
                    "Foreground keep-alive is on. Shizulu can stay ready after the recent-app card is closed."
                } else {
                    "Keep Wireless ADB available with a foreground service and disable battery optimization for Shizulu."
                }
                textSize = 13f
                setTextColor(COLORS.muted)
                setPadding(0, dp(5), 0, dp(10))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                keepAliveButton = compactButton(if (keepAliveEnabled) "Keep Alive: On" else "Keep Alive: Off", filled = keepAliveEnabled) {
                    setWirelessAdbKeepAlive(!settingsPrefs.getBoolean(KEY_WIRELESS_ADB_KEEP_ALIVE, false))
                }
                batteryButton = compactButton(if (batteryIgnored) "Battery: Allowed" else "Allow Battery", filled = batteryIgnored) {
                    requestBatteryOptimizationExemption()
                }
                addView(keepAliveButton, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(batteryButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(10) })
            })
        }
    }

    private fun shizuleLabPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(COLORS.surfaceAlt, dp(8), COLORS.outline, 1)

            addView(TextView(context).apply {
                text = "Shizule Lab"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
            })

            addView(TextView(context).apply {
                text = "Install safe test shizules or draft a new JSON shizule inside Shizulu."
                textSize = 13f
                setTextColor(COLORS.muted)
                setPadding(0, dp(5), 0, dp(10))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(compactButton("Install Tests", filled = false) { installBuiltInTestShizules() }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(compactButton("Module Maker", filled = true) { showShizuleMaker() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    leftMargin = dp(10)
                })
            })
        }
    }

    private fun rootlessPowerPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(COLORS.surfaceAlt, dp(8), COLORS.outline, 1)

            addView(TextView(context).apply {
                text = "Rootless Power Tools"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLORS.ink)
            })

            addView(TextView(context).apply {
                text = "Shell-level SU compatibility, RRO overlays, AppOps, and package commands through the selected backend. This does not grant arbitrary apps true system root."
                textSize = 13f
                setTextColor(COLORS.muted)
                setPadding(0, dp(5), 0, dp(10))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(compactButton("SU Bridge", filled = true) { showSuBridgeMenu() }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(compactButton("RRO", filled = false) { showRroMenu() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    leftMargin = dp(10)
                })
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(compactButton("AppOps", filled = false) { showAppOpsMenu() }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(compactButton("Packages", filled = false) { showPackageMenu() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    leftMargin = dp(10)
                })
            }, spacedParams(top = 10))
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
                    val enabled = savedRelease?.isReadyForCurrentBuild() == true
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
                text = "Choose light or dark mode and an optional accent color."
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
                orientation = LinearLayout.VERTICAL
                AccentTheme.entries.chunked(3).forEachIndexed { rowIndex, row ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        row.forEachIndexed { index, theme ->
                            addView(themeSwatch(theme, theme == accent), LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                                if (index > 0) leftMargin = dp(8)
                            })
                        }
                        repeat(3 - row.size) {
                            addView(View(context), LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
                        }
                    }, LinearLayout.LayoutParams(-1, dp(44)).apply {
                        if (rowIndex > 0) topMargin = dp(8)
                    })
                }
            })
        }
    }

    private fun themeSwatch(theme: AccentTheme, selected: Boolean): TextView {
        val mode = AppearanceMode.from(settingsPrefs.getString(KEY_APPEARANCE_MODE, null))
        val palette = theme.palette(mode)
        return TextView(this).apply {
            text = theme.label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (selected) palette.onPrimary else COLORS.ink)
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
            val wirelessMode = executionMode == ExecutionMode.WIRELESS_ADB
            val wirelessConfigured = wirelessAdbConfigured()

            if (::statusTitle.isInitialized) {
                statusTitle.text = if (wirelessMode) {
                    "Wireless ADB mode"
                } else {
                    when {
                        !binderAlive -> "Shizuku is not connected"
                        !permissionGranted -> "Permission needed"
                        uid == null -> "Ready to bind service"
                        else -> "Shizulu is ready"
                    }
                }
            }

            if (::statusSubtitle.isInitialized) {
                statusSubtitle.text = if (wirelessMode) {
                    if (wirelessConfigured) {
                        "ADB backend selected"
                    } else {
                        "Pair in Tools to run shizules"
                    }
                } else {
                    when {
                        !binderAlive -> "Start Shizuku to use this backend"
                        !permissionGranted -> "Grant shell access"
                        uid == null -> "Bind service to run shizules"
                        else -> "Bound as uid $uid"
                    }
                }
            }

            if (wirelessMode) {
                if (::shizukuChip.isInitialized) setChip(shizukuChip, "Wireless ADB", wirelessConfigured)
                if (::permissionChip.isInitialized) setChip(permissionChip, if (wirelessConfigured) "Configured" else "Needs pairing", wirelessConfigured)
                if (::serviceChip.isInitialized) setChip(serviceChip, if (wirelessConfigured) "ADB ready" else "ADB setup", wirelessConfigured)
            } else {
                if (::shizukuChip.isInitialized) setChip(shizukuChip, if (binderAlive) "Shizuku running" else "Shizuku offline", binderAlive)
                if (::permissionChip.isInitialized) setChip(permissionChip, if (permissionGranted) "Allowed" else "Needs grant", permissionGranted)
                if (::serviceChip.isInitialized) setChip(serviceChip, if (uid != null) "Service bound" else "Service idle", uid != null)
            }
            if (::backendChip.isInitialized) {
                when {
                    wirelessMode -> setChip(backendChip, if (wirelessConfigured) "Wireless ADB" else "Pair ADB", wirelessConfigured)
                    uid != null -> setChip(backendChip, "Ready", true)
                    !binderAlive -> setChip(backendChip, "Offline", false)
                    !permissionGranted -> setChip(backendChip, "Needs grant", false)
                    else -> setChip(backendChip, "Bind", false)
                }
            }

            if (::grantButton.isInitialized) {
                grantButton.text = when {
                    wirelessMode -> "Wireless ADB mode"
                    permissionGranted -> "Bind service"
                    else -> "Grant Shizuku"
                }
            }
        }
    }

    private fun renderDryRun() {
        if (::dryRunButton.isInitialized) {
            dryRunButton.text = if (dryRunEnabled) "Dry Run: On" else "Dry Run: Off"
            dryRunButton.setTextColor(if (dryRunEnabled) COLORS.warning else COLORS.ink)
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
        refreshModules()
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
        val hiddenBuiltIns = loadHiddenBuiltInProfileKeys()
        profilesList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton("Create Profile") { showCreateProfileDialog() }, LinearLayout.LayoutParams(0, dp(46), 1f))
            if (hiddenBuiltIns.isNotEmpty()) {
                addView(secondaryButton("Restore Defaults") { restoreBuiltInProfiles() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    leftMargin = dp(10)
                })
            }
        }, LinearLayout.LayoutParams(-1, -2))

        val profiles = PROFILES.filterNot { profileKey(it) in hiddenBuiltIns } + loadCustomProfiles()
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
                    addView(compactButton(if (dryRunEnabled) "Preview" else "Run", filled = missing == 0) { runProfile(profile) })
                    addView(compactButton("Delete", filled = false) {
                        confirmDeleteProfile(profile)
                    }, rowGapParams())
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
                    setTextColor(COLORS.ink)
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
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            REQUEST_IMPORT
        )
    }

    private fun installFromPickerResult(data: Intent) {
        val uris = buildList {
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index).uri?.let(::add)
                }
            }
            data.data?.let { single ->
                if (none { it == single }) add(single)
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No shizule selected", Toast.LENGTH_SHORT).show()
            return
        }

        if (uris.size == 1) {
            installFromUri(uris.first(), refreshAfterInstall = true)
            return
        }

        var installed = 0
        var failed = 0
        uris.forEach { uri ->
            if (installFromUri(uri, refreshAfterInstall = false, showToast = false)) installed++ else failed++
        }
        appendLog("Batch import complete: $installed installed, $failed failed")
        Toast.makeText(this, "Installed $installed shizule(s)${if (failed > 0) ", $failed failed" else ""}", Toast.LENGTH_LONG).show()
        refreshModules()
    }

    private fun installFromUri(uri: Uri) {
        installFromUri(uri, refreshAfterInstall = true)
    }

    private fun installFromUri(uri: Uri, refreshAfterInstall: Boolean, showToast: Boolean = true): Boolean {
        val raw = contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: return false

        return runCatching { Shizule.fromJson(raw) }
            .onSuccess { shizule ->
                installTrusted(raw, shizule, refreshAfterInstall, showToast)
            }
            .onFailure {
                appendLog("Install failed: ${it.message ?: "invalid shizule"}")
                if (showToast) Toast.makeText(this, it.message ?: "Invalid shizule", Toast.LENGTH_LONG).show()
            }
            .isSuccess
    }

    private fun installTrusted(raw: String, shizule: Shizule) {
        installTrusted(raw, shizule, refreshAfterInstall = true, showToast = true)
    }

    private fun installTrusted(raw: String, shizule: Shizule, refreshAfterInstall: Boolean, showToast: Boolean): Boolean {
        return runCatching { store.install(raw) }
            .onSuccess {
                appendLog("Installed shizule ${it.name} (${it.id})")
                if (showToast) Toast.makeText(this, "Installed ${it.name}", Toast.LENGTH_SHORT).show()
                if (refreshAfterInstall) refreshModules()
            }
            .onFailure {
                appendLog("Install failed: ${it.message ?: "invalid shizule"}")
                if (showToast) Toast.makeText(this, it.message ?: "Invalid shizule", Toast.LENGTH_LONG).show()
            }
            .isSuccess
    }

    private fun installBuiltInTestShizules() {
        var installed = 0
        var failed = 0
        builtInTestShizules().forEach { raw ->
            val shizule = runCatching { Shizule.fromJson(raw) }.getOrNull()
            if (shizule != null && installTrusted(raw, shizule, refreshAfterInstall = false, showToast = false)) {
                installed++
            } else {
                failed++
            }
        }
        appendLog("Built-in test shizules installed: $installed installed, $failed failed")
        Toast.makeText(this, "Installed $installed test shizule(s)${if (failed > 0) ", $failed failed" else ""}", Toast.LENGTH_LONG).show()
        refreshModules()
    }

    private fun showShizuleMaker() {
        val editor = EditText(this).apply {
            setText(blankShizuleTemplate())
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.START or Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 18
            setHorizontallyScrolling(false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(COLORS.ink)
            setHintTextColor(COLORS.muted)
            background = roundedRect(COLORS.surface, dp(8), COLORS.outline, 1)
        }

        val editorWrap = ScrollView(this).apply {
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(editor, LinearLayout.LayoutParams(-1, -2))
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Module Maker")
            .setMessage("Edit the JSON, then install it as a shizule.")
            .setView(editorWrap)
            .setPositiveButton("Install") { _, _ ->
                val raw = editor.text.toString()
                runCatching { Shizule.fromJson(raw) }
                    .onSuccess { shizule -> installTrusted(raw, shizule) }
                    .onFailure {
                        val message = it.message ?: "Invalid shizule JSON"
                        appendLog("Module Maker install failed: $message")
                        showOutput("Module Maker", "Invalid shizule:\n\n$message")
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSuBridgeMenu() {
        val enabled = settingsPrefs.getBoolean(KEY_SU_BRIDGE_ENABLED, false)
        val items = arrayOf(
            if (enabled) "Disable bridge endpoint" else "Enable bridge endpoint",
            "Max ADB Elevation",
            "Run su -c command",
            "Self-test",
            "ColorBlendr compatibility",
            "Install shell interceptors",
            "Install bridge shizule",
            "Install /data/local/tmp bridge script",
            "Bridge status",
            "Bridge API sample"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("SU Bridge")
            .setMessage("Shizulu now exposes a real opt-in bridge endpoint at content://$packageName.su for apps/modules that integrate with it. Normal APKs still cannot replace Android's system su path without root/system access.")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setSuBridgeEndpointEnabled(!enabled)
                    1 -> runSuBridgeElevation()
                    2 -> showSuBridgeCommandDialog()
                    3 -> runSuBridgeSelfTest()
                    4 -> showColorBlendrCompatibility()
                    5 -> runPowerCommand("Install Shell Interceptors", shellInterceptorInstallCommand(), "com.shizulu.shell_interceptor")
                    6 -> installSuBridgeShizule()
                    7 -> runPowerCommand("Install SU Bridge Script", suBridgeInstallCommand(), "com.shizulu.su_bridge")
                    8 -> showSuBridgeStatus()
                    9 -> showSuBridgeApiSample()
                }
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun setSuBridgeEndpointEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_SU_BRIDGE_ENABLED, enabled).apply()
        appendLog("SU Bridge endpoint ${if (enabled) "enabled" else "disabled"}")
        Toast.makeText(this, if (enabled) "SU Bridge endpoint enabled" else "SU Bridge endpoint disabled", Toast.LENGTH_SHORT).show()
        if (currentPage == Page.TOOLS) showPage(Page.TOOLS)
    }

    private fun runSuBridgeElevation() {
        settingsPrefs.edit().putBoolean(KEY_SU_BRIDGE_ENABLED, true).apply()
        appendLog("SU Bridge max ADB elevation requested")
        runPowerCommand("Max ADB Elevation", maxAdbElevationCommand(), "com.shizulu.su_bridge")
    }

    private fun showSuBridgeCommandDialog() {
        promptForLines(
            title = "Run SU Command",
            message = "Enter a command. Shizulu will run it like su -c through the selected backend.",
            initial = "id"
        ) { lines ->
            val command = lines.joinToString("\n").trim()
            if (command.isBlank()) {
                Toast.makeText(this, "Command is required.", Toast.LENGTH_SHORT).show()
            } else {
                runPowerCommand("SU Bridge", command, "com.shizulu.su_bridge")
            }
        }
    }

    private fun showSuBridgeStatus() {
        runCatching { SuBridgeExecutor(this).status() }
            .onSuccess { status ->
                showOutput(
                    "SU Bridge Status",
                    "$status\nProvider: content://$packageName.su\nAccess: endpoint is open only when SU Bridge is enabled"
                )
            }
            .onFailure {
                showOutput("SU Bridge Status", it.message ?: it.javaClass.simpleName)
            }
    }

    private fun showSuBridgeApiSample() {
        showOutput(
            "SU Bridge API",
            """
            Authority:
            content://$packageName.su

            Access:
            Enable SU Bridge first. The endpoint is disabled by default.

            Provider calls:
            call("status", null, null)
            call("exec", null, Bundle().apply { putString("command", "id") })
            call("su", "su 0 -c id", null)
            call("su-c", "su -c id", null)

            Compatibility shim:
            Run "Install /data/local/tmp bridge script", then point compatible apps to:
            /data/local/tmp/su

            Hardcoded /system/bin/su calls cannot be intercepted without root/system access.

            Returned Bundle:
            success: Boolean
            message: String
            output: String
            """.trimIndent()
        )
    }

    private fun runSuBridgeSelfTest() {
        if (!settingsPrefs.getBoolean(KEY_SU_BRIDGE_ENABLED, false)) {
            showOutput("SU Bridge Self-Test", "Enable the SU Bridge endpoint first.")
            return
        }
        executor.execute {
            runCatching { SuBridgeExecutor(applicationContext).execute("com.shizulu.su_bridge.selftest", "id; echo bridge=ok") }
                .onSuccess { output ->
                    appendLog("SU Bridge self-test succeeded")
                    mainHandler.post { showOutput("SU Bridge Self-Test", output) }
                }
                .onFailure {
                    val message = it.message ?: it.javaClass.simpleName
                    appendLog("SU Bridge self-test failed: $message")
                    mainHandler.post { showOutput("SU Bridge Self-Test", "Failed: $message") }
                }
        }
    }

    private fun showColorBlendrCompatibility() {
        android.app.AlertDialog.Builder(this)
            .setTitle("ColorBlendr Compatibility")
            .setMessage("ColorBlendr root mode uses libsu, so Android will not let Shizulu intercept that root request from another normal app. This helper pushes ColorBlendr onto its rootless Shizuku-compatible route and enables the ADB-level theming flags it exposes.")
            .setItems(arrayOf("Run compatibility setup", "Show technical reason")) { _, which ->
                when (which) {
                    0 -> runPowerCommand(
                        "ColorBlendr Compatibility",
                        colorBlendrCompatibilityCommand(),
                        "com.shizulu.colorblendr_bridge"
                    )
                    1 -> showOutput(
                        "ColorBlendr Root Path",
                        """
                        ColorBlendr root mode calls topjohnwu libsu:
                        - Shell.isAppGrantedRoot()
                        - Shell.cmd(...).exec()
                        - RootService.bind(...)

                        Those calls resolve through the device su binary and Magisk/KernelSU-style root service. A normal APK cannot replace /system/bin/su, modify ColorBlendr's process PATH, or answer libsu's root grant request for another app.

                        What Shizulu can do:
                        - Run the same ADB/shell-level commands through Shizulu.
                        - Install /data/local/tmp/su for apps that support a custom su path.
                        - Install /data/local/tmp/shizulu-bin wrappers for apps/tools that can run through a custom shell PATH.
                        - Configure ColorBlendr's exported preferences into Shizuku/rootless mode so it uses the non-root path instead of asking libsu.
                        """.trimIndent()
                    )
                }
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun installSuBridgeShizule() {
        val raw = suBridgeShizuleJson()
        runCatching { Shizule.fromJson(raw) }
            .onSuccess { shizule ->
                if (installTrusted(raw, shizule, refreshAfterInstall = true, showToast = true)) {
                    appendLog("Installed SU Bridge shizule")
                }
            }
            .onFailure {
                val message = it.message ?: "Invalid SU Bridge shizule"
                appendLog("SU Bridge shizule install failed: $message")
                showOutput("SU Bridge", message)
            }
    }

    private fun showRroMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("RRO Overlays")
            .setItems(arrayOf("List overlays", "Enable overlay package", "Disable overlay package", "Fabricated overlay template")) { _, which ->
                when (which) {
                    0 -> runPowerCommand("RRO Overlays", "cmd overlay list", "com.shizulu.rro")
                    1 -> promptForShellToken("Enable overlay", "Overlay package name, or fabricated id like com.android.shell:name", "com.example.overlay") { pkg ->
                        runPowerCommand("Enable Overlay", "cmd overlay enable --user 0 ${pkg.shellQuote()}", "com.shizulu.rro")
                    }
                    2 -> promptForShellToken("Disable overlay", "Overlay package name, or fabricated id like com.android.shell:name", "com.example.overlay") { pkg ->
                        runPowerCommand("Disable Overlay", "cmd overlay disable --user 0 ${pkg.shellQuote()}", "com.shizulu.rro")
                    }
                    3 -> showOutput(
                        "Fabricated Overlay Template",
                        "Android's shell supports fabricated overlays on some builds, but exact resource names are ROM-specific.\n\nTemplate:\ncmd overlay fabricate --target <target.package> --name <overlayName> <resourceName> <type> <value>\ncmd overlay enable --user 0 com.android.shell:shizulu.<overlayName>\n\nUse List overlays first, then run a custom shizule command once you know the target resource."
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppOpsMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Advanced AppOps")
            .setItems(arrayOf("Get package AppOps", "Set AppOp mode", "Reset package AppOps")) { _, which ->
                when (which) {
                    0 -> promptForPackage("Get AppOps", "Package name") { pkg ->
                        runPowerCommand("AppOps: $pkg", "cmd appops get ${pkg.shellQuote()}", "com.shizulu.appops")
                    }
                    1 -> promptForLines(
                        title = "Set AppOp",
                        message = "Enter package, op, and mode on separate lines. Example mode: allow, ignore, deny, foreground, default.",
                        initial = "com.example.app\nRUN_IN_BACKGROUND\nignore"
                    ) { lines ->
                        val pkg = lines.getOrNull(0)?.trim().orEmpty()
                        val op = lines.getOrNull(1)?.trim().orEmpty()
                        val mode = lines.getOrNull(2)?.trim().orEmpty()
                        val validPackage = validPackageOrToast(pkg) ?: return@promptForLines
                        if (!op.isSafeShellToken() || !mode.isSafeShellToken()) {
                            Toast.makeText(this, "Op and mode cannot contain spaces or shell characters.", Toast.LENGTH_LONG).show()
                            return@promptForLines
                        }
                        runPowerCommand("Set AppOp", "cmd appops set ${validPackage.shellQuote()} $op $mode", "com.shizulu.appops")
                    }
                    2 -> promptForPackage("Reset AppOps", "Package name") { pkg ->
                        runPowerCommand("Reset AppOps", "cmd appops reset ${pkg.shellQuote()}", "com.shizulu.appops")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPackageMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Package Manipulation")
            .setItems(arrayOf("List user packages", "Enable package", "Disable package for user 0", "Clear package data", "Optimize package")) { _, which ->
                when (which) {
                    0 -> runPowerCommand("User Packages", "cmd package list packages -3", "com.shizulu.package_tools")
                    1 -> promptForPackage("Enable package", "Package name") { pkg ->
                        runPowerCommand("Enable Package", "pm enable ${pkg.shellQuote()}", "com.shizulu.package_tools")
                    }
                    2 -> promptForPackage("Disable package", "Package name") { pkg ->
                        runPowerCommand("Disable Package", "pm disable-user --user 0 ${pkg.shellQuote()}", "com.shizulu.package_tools")
                    }
                    3 -> promptForPackage("Clear data", "Package name") { pkg ->
                        runPowerCommand("Clear Package Data", "pm clear ${pkg.shellQuote()}", "com.shizulu.package_tools")
                    }
                    4 -> promptForPackage("Optimize package", "Package name") { pkg ->
                        runPowerCommand("Optimize Package", "cmd package compile -m speed-profile -f ${pkg.shellQuote()}", "com.shizulu.package_tools")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForPackage(title: String, hint: String, onPackage: (String) -> Unit) {
        promptForLines(title, hint, "com.example.app") { lines ->
            validPackageOrToast(lines.firstOrNull().orEmpty().trim())?.let(onPackage)
        }
    }

    private fun promptForShellToken(title: String, message: String, initial: String, onToken: (String) -> Unit) {
        promptForLines(title, message, initial) { lines ->
            val token = lines.firstOrNull().orEmpty().trim()
            if (token.isSafeShellToken()) {
                onToken(token)
            } else {
                Toast.makeText(this, "Value cannot contain spaces or shell characters.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptForLines(title: String, message: String, initial: String, onLines: (List<String>) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.START or Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = initial.lines().size.coerceAtLeast(2)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(COLORS.ink)
            setHintTextColor(COLORS.muted)
            background = roundedRect(COLORS.surface, dp(8), COLORS.outline, 1)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Run") { _, _ ->
                onLines(input.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun validPackageOrToast(value: String): String? {
        if (value.matches(PACKAGE_NAME_PATTERN)) return value
        Toast.makeText(this, "Invalid package name.", Toast.LENGTH_LONG).show()
        return null
    }

    private fun runPowerCommand(title: String, command: String, moduleId: String) {
        if (dryRunEnabled) {
            appendLog("Dry run power command: $title")
            showOutput(title, "Dry run: no command executed.\n\n$ $command")
            return
        }

        if (executionMode == ExecutionMode.WIRELESS_ADB) {
            val pairingCode = settingsPrefs.getString(KEY_ADB_PAIRING_CODE, "").orEmpty()
            val port = settingsPrefs.getInt(KEY_ADB_PAIR_PORT, 0)
            if (pairingCode.isBlank() || port <= 0) {
                appendLog("$title blocked: Wireless ADB not configured")
                showOutput(title, "Pair Wireless ADB first, or switch the execution backend to Shizuku.")
                return
            }
            appendLog("Power command started over Wireless ADB: $title")
            executor.execute {
                runCatching { WirelessAdbRunner(applicationContext).runCommand(moduleId, command, pairingCode, port) }
                    .onSuccess { result ->
                        appendLog("Power command finished: $title")
                        mainHandler.post { showOutput(title, result.output) }
                    }
                    .onFailure {
                        val message = it.message ?: it.javaClass.simpleName
                        appendLog("Power command failed: $title: $message")
                        mainHandler.post { showOutput(title, "Failed: $message") }
                    }
            }
            return
        }

        if (!hasShizukuPermission()) {
            requestShizukuPermission()
            return
        }
        bindUserService()
        val currentService = service
        if (currentService == null) {
            appendLog("$title delayed: Shizulu service is still binding")
            Toast.makeText(this, "Binding Shizulu service. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        appendLog("Power command started over Shizuku: $title")
        executor.execute {
            runCatching { currentService.runShizuleCommand(moduleId, command) }
                .onSuccess { result ->
                    appendLog("Power command finished: $title")
                    mainHandler.post { showOutput(title, result) }
                }
                .onFailure {
                    val message = it.message ?: it.javaClass.simpleName
                    appendLog("Power command failed: $title: $message")
                    mainHandler.post { showOutput(title, "Failed: $message") }
                }
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

    private fun setWirelessAdbKeepAlive(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_WIRELESS_ADB_KEEP_ALIVE, enabled).apply()
        if (enabled) {
            requestNotificationPermissionIfNeeded()
            requestBatteryOptimizationExemption()
            WirelessAdbKeepAliveService.start(this)
            appendLog("Wireless ADB keep-alive enabled")
            Toast.makeText(this, "Wireless ADB keep-alive enabled", Toast.LENGTH_SHORT).show()
        } else {
            WirelessAdbKeepAliveService.stop(this)
            appendLog("Wireless ADB keep-alive disabled")
            Toast.makeText(this, "Wireless ADB keep-alive disabled", Toast.LENGTH_SHORT).show()
        }
        if (currentPage == Page.TOOLS) showPage(Page.TOOLS)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun batteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun requestBatteryOptimizationExemption() {
        if (batteryOptimizationIgnored()) {
            Toast.makeText(this, "Battery optimization already disabled for Shizulu", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
                    .apply {
                        if (port > 0) putInt(KEY_ADB_CONNECT_PORT, port) else remove(KEY_ADB_CONNECT_PORT)
                    }
                    .apply()
                appendLog("Wireless ADB config saved: code=${pairingCode.maskPairingCode()} port=$port cached=${if (port > 0) port else "cleared"}")
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

    private fun autoRepairWirelessAdbBackend() {
        val pairingCode = settingsPrefs.getString(KEY_ADB_PAIRING_CODE, "").orEmpty()
        val port = settingsPrefs.getInt(KEY_ADB_PAIR_PORT, 0)
        if (pairingCode.isBlank() || port <= 0) {
            appendLog("Wireless ADB auto repair: not configured")
            showOutput(
                "Wireless ADB Auto Repair",
                "Pairing required.\n\nSave a fresh pairing code and port first, then run Auto Repair."
            )
            return
        }

        settingsPrefs.edit().remove(KEY_ADB_CONNECT_PORT).apply()
        appendLog("Wireless ADB auto repair started: cleared cached connect port; code=${pairingCode.maskPairingCode()} port=$port")
        Toast.makeText(this, "Repairing Wireless ADB...", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                WirelessAdbRunner(applicationContext).test(pairingCode, port)
            }.onSuccess { result ->
                appendLog("Wireless ADB auto repair succeeded")
                mainHandler.post {
                    showOutput(
                        "Wireless ADB Auto Repair",
                        "Cleared stale cache and reconnected.\n\n${result.output}"
                    )
                }
            }.onFailure {
                val message = it.message ?: it.javaClass.simpleName
                appendLog("Wireless ADB auto repair failed: $message")
                mainHandler.post {
                    showOutput(
                        "Wireless ADB Auto Repair",
                        "Failed after clearing stale cache.\n\n$message"
                    )
                }
            }
        }.start()
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

    private fun builtInTestShizules(): List<String> {
        return listOf(
            shizuleJson(
                id = "com.shizulu.test.device_probe",
                name = "Device Probe",
                version = "1.0.0",
                description = "Safe test commands for checking shell identity and device properties.",
                actions = listOf(
                    "Device Info" to listOf(
                        "id",
                        "getprop ro.product.model",
                        "getprop ro.build.version.release"
                    ),
                    "Storage Check" to listOf(
                        "df /data"
                    )
                )
            ),
            shizuleJson(
                id = "com.shizulu.test.settings_reader",
                name = "Settings Reader",
                version = "1.0.0",
                description = "Read-only settings checks for testing ADB privileges without changing anything.",
                actions = listOf(
                    "Read Animation Scales" to listOf(
                        "settings get global window_animation_scale",
                        "settings get global transition_animation_scale",
                        "settings get global animator_duration_scale"
                    ),
                    "Read Brightness" to listOf(
                        "settings get system screen_brightness"
                    )
                )
            ),
            shizuleJson(
                id = "com.shizulu.test.echo_lab",
                name = "Echo Lab",
                version = "1.0.0",
                description = "A tiny output-only shizule for confirming command execution and environment variables.",
                actions = listOf(
                    "Echo Test" to listOf(
                        "echo Shizulu test OK",
                        "echo module=\$SHIZULU_MODULE_ID",
                        "date"
                    )
                )
            )
        )
    }

    private fun suBridgeShizuleJson(): String {
        return shizuleJson(
            id = "com.shizulu.su_bridge",
            name = "SU Bridge",
            version = "1.0.0",
            description = "Shell-level su compatibility helpers for Shizulu-aware apps and apps that support a custom su path.",
            actions = listOf(
                "Bridge Status" to listOf(
                    "id; echo SHIZULU=\$SHIZULU; echo API=\$SHIZULU_API_VERSION; echo MODULE=\$SHIZULU_MODULE_ID",
                    "if [ -x /data/local/tmp/su ]; then /data/local/tmp/su -c 'id; echo shim=ok'; else echo /data/local/tmp/su missing; fi"
                ),
                "Install Bridge Script" to listOf(
                    suBridgeInstallCommand()
                ),
                "Max ADB Elevation" to listOf(
                    maxAdbElevationCommand()
                ),
                "Remove Bridge Script" to listOf(
                    "rm -f /data/local/tmp/shizulu-su /data/local/tmp/su; echo removed Shizulu SU Bridge scripts"
                )
            )
        )
    }

    private fun suBridgeInstallCommand(): String {
        return listOf(
            "cat > /data/local/tmp/shizulu-su <<'EOF'",
            "#!/system/bin/sh",
            "uri='content://com.shizulu.manager.su'",
            "version='Shizulu SU Bridge shim 1.2'",
            "command=''",
            "while [ \"\$#\" -gt 0 ]; do",
            "  case \"\$1\" in",
            "    -v|--version)",
            "      echo \"\$version\"",
            "      exit 0",
            "      ;;",
            "    -V)",
            "      echo 1",
            "      exit 0",
            "      ;;",
            "    -h|--help)",
            "      echo 'usage: su [options] [-c command]'",
            "      exit 0",
            "      ;;",
            "    -c|--command)",
            "      shift",
            "      command=\"\$*\"",
            "      break",
            "      ;;",
            "    -c*)",
            "      command=\"\${1#-c}\"",
            "      shift",
            "      if [ \"\$#\" -gt 0 ]; then command=\"\$command \$*\"; fi",
            "      break",
            "      ;;",
            "    -s|--shell|-Z|--context)",
            "      shift",
            "      if [ \"\$#\" -gt 0 ]; then shift; fi",
            "      ;;",
            "    -p|-l|-m|-mm|-M|--mount-master|--preserve-environment)",
            "      shift",
            "      ;;",
            "    0|root|shell)",
            "      shift",
            "      ;;",
            "    *)",
            "      command=\"\$*\"",
            "      break",
            "      ;;",
            "  esac",
            "done",
            "if [ -z \"\$command\" ] && [ ! -t 0 ]; then",
            "  command=\"\$(cat)\"",
            "fi",
            "if [ -z \"\$command\" ]; then",
            "  echo 'Shizulu SU Bridge: interactive shells cannot be proxied without root.'",
            "  exit 1",
            "fi",
            "result=\"\$(/system/bin/cmd content call --uri \"\$uri\" --method su --arg \"su -c \$command\" 2>&1)\"",
            "echo \"\$result\"",
            "echo \"\$result\" | grep -q 'success=false' && exit 1",
            "exit 0",
            "EOF",
            "cp /data/local/tmp/shizulu-su /data/local/tmp/su",
            "chmod 755 /data/local/tmp/shizulu-su /data/local/tmp/su",
            "echo installed /data/local/tmp/shizulu-su",
            "echo installed /data/local/tmp/su",
            "echo compatible apps can use /data/local/tmp/su as their custom su path"
        ).joinToString("\n")
    }

    private fun shellInterceptorInstallCommand(): String {
        return """
            dir=/data/local/tmp/shizulu-bin
            uri='content://com.shizulu.manager.su'
            mkdir -p "${'$'}dir"
            cat > "${'$'}dir/su" <<'EOF'
            #!/system/bin/sh
            uri='content://com.shizulu.manager.su'
            version='Shizulu SU Bridge shim 1.3'
            command=''
            while [ "${'$'}#" -gt 0 ]; do
              case "${'$'}1" in
                -v|--version)
                  echo "${'$'}version"
                  exit 0
                  ;;
                -V)
                  echo 1
                  exit 0
                  ;;
                -h|--help)
                  echo 'usage: su [options] [-c command]'
                  exit 0
                  ;;
                -c|--command)
                  shift
                  command="${'$'}*"
                  break
                  ;;
                -c*)
                  command="${'$'}{1#-c}"
                  shift
                  if [ "${'$'}#" -gt 0 ]; then command="${'$'}command ${'$'}*"; fi
                  break
                  ;;
                -s|--shell|-Z|--context)
                  shift
                  if [ "${'$'}#" -gt 0 ]; then shift; fi
                  ;;
                -p|-l|-m|-mm|-M|--mount-master|--preserve-environment)
                  shift
                  ;;
                0|root|shell)
                  shift
                  ;;
                *)
                  command="${'$'}*"
                  break
                  ;;
              esac
            done
            if [ -z "${'$'}command" ] && [ ! -t 0 ]; then
              command="${'$'}(/system/bin/cat)"
            fi
            if [ -z "${'$'}command" ]; then
              echo 'Shizulu SU Bridge: interactive shells cannot be proxied without root.'
              exit 1
            fi
            result="${'$'}(/system/bin/cmd content call --uri "${'$'}uri" --method su --arg "su -c ${'$'}command" 2>&1)"
            echo "${'$'}result"
            echo "${'$'}result" | /system/bin/grep -q 'success=false' && exit 1
            exit 0
            EOF
            make_wrapper() {
              tool="${'$'}1"
              cat > "${'$'}dir/${'$'}tool" <<EOF
            #!/system/bin/sh
            uri='content://com.shizulu.manager.su'
            command='${'$'}tool '"\${'$'}*"
            result="\$(/system/bin/cmd content call --uri "\${'$'}uri" --method exec --arg "\${'$'}command" 2>&1)"
            echo "\${'$'}result"
            echo "\${'$'}result" | /system/bin/grep -q 'success=false' && exit 1
            exit 0
            EOF
            }
            make_wrapper pm
            make_wrapper am
            make_wrapper settings
            make_wrapper cmd
            make_wrapper appops
            cat > "${'$'}dir/shizulu-shell" <<'EOF'
            #!/system/bin/sh
            export PATH="/data/local/tmp/shizulu-bin:${'$'}PATH"
            exec /system/bin/sh "${'$'}@"
            EOF
            chmod 755 "${'$'}dir" "${'$'}dir/"*
            cp "${'$'}dir/su" /data/local/tmp/su
            chmod 755 /data/local/tmp/su
            echo "Installed Shizulu shell interceptors:"
            ls -l "${'$'}dir"
            echo
            echo "Use /data/local/tmp/shizulu-bin/su as a custom su path."
            echo "Use /data/local/tmp/shizulu-bin/shizulu-shell as a custom shell, or prepend ${'$'}dir to PATH."
            echo "This cannot replace hardcoded /system/bin/su or force another app's private PATH without root."
        """.trimIndent()
    }

    private fun colorBlendrCompatibilityCommand(): String {
        return """
            pkg=com.drdisagree.colorblendr
            prefs=content://${'$'}pkg/${'$'}{pkg}_preferences
            echo "Shizulu ColorBlendr compatibility setup"
            echo "package=${'$'}pkg"
            echo
            if ! pm path "${'$'}pkg" >/dev/null 2>&1; then
              echo "ColorBlendr is not installed."
              exit 1
            fi
            echo "ColorBlendr is installed."
            echo
            echo "Important: ColorBlendr root mode uses libsu. Shizulu cannot intercept that root request from outside the app."
            echo "Switching ColorBlendr to its Shizuku/rootless preference path and enabling theming flags."
            echo
            put_string() {
              echo "$ content insert ${'$'}prefs/${'$'}1 = ${'$'}2"
              cmd content insert --uri "${'$'}prefs/${'$'}1" --bind type:i:1 --bind value:s:"${'$'}2" 2>&1
              echo
            }
            put_bool() {
              echo "$ content insert ${'$'}prefs/${'$'}1 = ${'$'}2"
              cmd content insert --uri "${'$'}prefs/${'$'}1" --bind type:i:6 --bind value:b:"${'$'}2" 2>&1
              echo
            }
            put_string workingMethod SHIZUKU
            put_bool shizukuThemingEnabled true
            put_bool themingEnabled true
            put_bool wirelessAdbThemingEnabled false
            echo "Current system Material You setting:"
            settings get secure theme_customization_overlay_packages 2>&1
            echo
            echo "Restarting ColorBlendr so it reloads preferences."
            am force-stop "${'$'}pkg" 2>&1
            monkey -p "${'$'}pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
            echo "Done. In ColorBlendr, choose Shizuku/rootless mode. Root-only ColorBlendr features still require real root because they are gated by ColorBlendr's own isRootMode/libsu checks."
        """.trimIndent()
    }

    private fun maxAdbElevationCommand(): String {
        val pkg = packageName.shellQuote()
        return """
            pkg=$pkg
            echo "Shizulu max ADB elevation"
            echo "package=${'$'}pkg"
            echo "uid=$(id)"
            echo
            run() {
              echo "$ ${'$'}*"
              "$@" 2>&1
              code=${'$'}?
              echo "exit=${'$'}code"
              echo
            }
            echo "Granting shell-grantable permissions..."
            run pm grant "${'$'}pkg" android.permission.WRITE_SECURE_SETTINGS
            run pm grant "${'$'}pkg" android.permission.READ_LOGS
            run pm grant "${'$'}pkg" android.permission.DUMP
            run pm grant "${'$'}pkg" android.permission.POST_NOTIFICATIONS
            echo "Applying appops..."
            run cmd appops set "${'$'}pkg" GET_USAGE_STATS allow
            run cmd appops set "${'$'}pkg" PACKAGE_USAGE_STATS allow
            run cmd appops set "${'$'}pkg" MANAGE_EXTERNAL_STORAGE allow
            run cmd appops set "${'$'}pkg" RUN_ANY_IN_BACKGROUND allow
            run cmd appops set "${'$'}pkg" RUN_IN_BACKGROUND allow
            run cmd appops set "${'$'}pkg" REQUEST_INSTALL_PACKAGES allow
            echo "Applying background persistence allowances..."
            run dumpsys deviceidle whitelist +"${'$'}pkg"
            run cmd deviceidle whitelist +"${'$'}pkg"
            echo "Verifying package state..."
            run cmd appops get "${'$'}pkg"
            run dumpsys package "${'$'}pkg"
            echo "Elevation attempt complete. Commands that show non-zero exit were blocked by this Android build, but every shell-accessible elevation was attempted."
        """.trimIndent()
    }

    private fun blankShizuleTemplate(): String {
        return shizuleJson(
            id = "com.example.my_shizule",
            name = "My Shizule",
            version = "1.0.0",
            description = "Describe what this shizule does.",
            actions = listOf(
                "Run" to listOf(
                    "echo Hello from Shizulu"
                ),
                "Restore" to listOf(
                    "echo Restore action goes here"
                )
            )
        )
    }

    private fun shizuleJson(
        id: String,
        name: String,
        version: String,
        description: String,
        actions: List<Pair<String, List<String>>>
    ): String {
        return JSONObject().apply {
            put("schema", 1)
            put("id", id)
            put("name", name)
            put("version", version)
            put("description", description)
            put("actions", JSONArray().apply {
                actions.forEachIndexed { index, action ->
                    put(JSONObject().apply {
                        put("id", action.first.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "action_$index" })
                        put("label", action.first)
                        put("commands", JSONArray().apply {
                            action.second.forEach { command ->
                                put(JSONObject().apply { put("exec", command) })
                            }
                        })
                    })
                }
            })
        }.toString(2)
    }

    private fun dryRunAction(shizule: Shizule, action: ShizuleAction) {
        appendLog("Dry run started: ${shizule.name}/${action.label}")
        val preview = buildDryRunPreview(listOf(shizule to action))
        action.commands.forEachIndexed { index, command ->
            appendLog("Dry run ${shizule.name}/${action.label} command ${index + 1}: ${command.exec}")
        }
        appendLog("Dry run finished: ${shizule.name}/${action.label}")
        showOutput("${shizule.name} Dry Run", preview)
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

        if (dryRunEnabled) {
            dryRunProfile(profile, shizules)
            return
        }

        appendLog("Started profile ${profile.name}")
        resolveProfileSteps(profile, shizules).forEach { (shizule, action) ->
            runAction(shizule, action)
        }
    }

    private fun dryRunProfile(profile: ShizuluProfile, shizules: List<Shizule>) {
        val actions = resolveProfileSteps(profile, shizules)
        appendLog("Dry run started profile ${profile.name} (${actions.size} action(s))")
        actions.forEach { (shizule, action) ->
            action.commands.forEachIndexed { index, command ->
                appendLog("Dry run ${profile.name}/${shizule.name}/${action.label} command ${index + 1}: ${command.exec}")
            }
        }
        appendLog("Dry run finished profile ${profile.name}")
        showOutput("${profile.name} Dry Run", buildDryRunPreview(actions, profile.name))
    }

    private fun resolveProfileSteps(profile: ShizuluProfile, shizules: List<Shizule>): List<Pair<Shizule, ShizuleAction>> {
        return profile.steps.map { step ->
            val shizule = shizules.first { it.id == step.moduleId }
            shizule to shizule.actions.first { it.id == step.actionId }
        }
    }

    private fun buildDryRunPreview(actions: List<Pair<Shizule, ShizuleAction>>, profileName: String? = null): String {
        val backend = if (executionMode == ExecutionMode.WIRELESS_ADB) "Wireless ADB" else "Shizuku"
        val commandCount = actions.sumOf { it.second.commands.size }
        return buildString {
            append("Dry run: no commands executed.\n")
            append("Backend: ").append(backend).append('\n')
            append("Environment: SHIZULU=1, SHIZULU_API_VERSION=1, SHIZULU_MODULE_ID=<module id>\n")
            if (profileName != null) append("Profile: ").append(profileName).append('\n')
            append("Actions: ").append(actions.size).append('\n')
            append("Commands: ").append(commandCount).append("\n\n")

            if (actions.isEmpty()) {
                append("No actions selected.")
                return@buildString
            }

            actions.forEachIndexed { actionIndex, (shizule, action) ->
                append(actionIndex + 1).append(". ").append(shizule.name).append(" / ").append(action.label).append('\n')
                append("   Module: ").append(shizule.id).append('\n')
                if (action.commands.isEmpty()) {
                    append("   No commands in this action.\n")
                } else {
                    action.commands.forEachIndexed { commandIndex, command ->
                        append("   ").append(commandIndex + 1).append(") ").append(command.exec).append('\n')
                    }
                }
                if (actionIndex < actions.lastIndex) append('\n')
            }
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

    private fun confirmDeleteProfile(profile: ShizuluProfile) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete profile?")
            .setMessage(
                if (profile.custom) {
                    "${profile.name} will be removed from Shizulu. This does not delete any installed shizules."
                } else {
                    "${profile.name} is a built-in profile. It will be hidden from Shizulu, and you can bring it back with Restore Defaults."
                }
            )
            .setPositiveButton("Delete") { _, _ -> deleteProfile(profile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteProfile(profile: ShizuluProfile) {
        if (!profile.custom) {
            hideBuiltInProfile(profile)
            return
        }
        val before = loadCustomProfiles()
        val remaining = before.filterNot { it.name == profile.name && it.steps == profile.steps }
        if (remaining.size == before.size) {
            Toast.makeText(this, "Profile was already removed", Toast.LENGTH_SHORT).show()
            return
        }
        settingsPrefs.edit().putString(KEY_CUSTOM_PROFILES, profilesToJson(remaining).toString()).apply()
        appendLog("Deleted profile ${profile.name}")
        Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show()
        refreshModules()
    }

    private fun hideBuiltInProfile(profile: ShizuluProfile) {
        val hidden = loadHiddenBuiltInProfileKeys() + profileKey(profile)
        settingsPrefs.edit().putString(KEY_HIDDEN_BUILTIN_PROFILES, JSONArray(hidden.toList()).toString()).apply()
        appendLog("Deleted built-in profile ${profile.name}")
        Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show()
        refreshModules()
    }

    private fun restoreBuiltInProfiles() {
        settingsPrefs.edit().remove(KEY_HIDDEN_BUILTIN_PROFILES).apply()
        appendLog("Restored built-in profiles")
        Toast.makeText(this, "Default profiles restored", Toast.LENGTH_SHORT).show()
        refreshModules()
    }

    private fun loadHiddenBuiltInProfileKeys(): Set<String> {
        val raw = settingsPrefs.getString(KEY_HIDDEN_BUILTIN_PROFILES, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun profileKey(profile: ShizuluProfile): String {
        return buildString {
            append(profile.name)
            append('|')
            profile.steps.forEach { step ->
                append(step.moduleId).append('/').append(step.actionId).append(';')
            }
        }
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
            put("hiddenBuiltInProfiles", JSONArray(loadHiddenBuiltInProfileKeys().toList()))
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
            val hiddenBuiltIns = backup.optJSONArray("hiddenBuiltInProfiles") ?: JSONArray()
            settingsPrefs.edit()
                .putBoolean(KEY_DRY_RUN, backup.optBoolean("dryRun", dryRunEnabled))
                .putString(KEY_CUSTOM_PROFILES, customProfiles.toString())
                .putString(KEY_HIDDEN_BUILTIN_PROFILES, hiddenBuiltIns.toString())
                .putString(KEY_APPEARANCE_MODE, backup.optString("appearanceMode", AppearanceMode.LIGHT.name))
                .putString(KEY_ACCENT_THEME, backup.optString("accentTheme", AccentTheme.DEFAULT.name))
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
            runCatching {
                val release = fetchLatestRelease()
                release.copy(
                    checkedAgainst = BuildConfig.GIT_SHA,
                    updateAvailable = releaseIsAheadOfCurrent(release)
                )
            }
                .onSuccess { release ->
                    latestRelease = release
                    val updateAvailable = release.isReadyForCurrentBuild()
                    appendLog("Update check ${if (updateAvailable) "found newer ${release.tag}" else "blocked stale/current ${release.tag}"}")
                    mainHandler.post {
                        if (updateAvailable) {
                            val status = "Update available: ${release.tag}\nCurrent: ${BuildConfig.GIT_SHA}\nLatest: ${release.commitSha ?: "unknown commit"}"
                            updaterStatusText.text = status
                            saveUpdaterState(status, release)
                            setUpdaterButton(true)
                        } else {
                            val status = "You're up to date.\nCurrent build: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})\nLatest release is not ahead of this app."
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
        if (release == null || !release.isReadyForCurrentBuild()) {
            val status = "Run Check before updating. Shizulu blocks stale release installs so it cannot downgrade your UI."
            if (::updaterStatusText.isInitialized) updaterStatusText.text = status
            saveUpdaterState(status, null)
            setUpdaterButton(false)
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
            if (name.isShizuluManagerApkName() && url.isNotBlank()) {
                apkName = name
                apkUrl = url
                break
            }
        }
        require(apkUrl.isNotBlank()) { "Latest release has no Shizulu manager APK asset." }
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

    private fun releaseIsAheadOfCurrent(release: GithubRelease): Boolean {
        val latest = release.commitSha?.takeIf { it.isNotBlank() } ?: return false
        val current = BuildConfig.GIT_SHA.takeIf { it.isNotBlank() && it != "unknown" } ?: return false
        if (latest.startsWith(current, ignoreCase = true) || current.startsWith(latest, ignoreCase = true)) {
            return false
        }
        val compareUrl = String.format(Locale.US, GITHUB_COMPARE_URL, current, latest)
        val status = JSONObject(httpGet(compareUrl)).optString("status")
        return status.equals("ahead", ignoreCase = true)
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
        validateUpdateApk(apk)
        appendLog("Update downloaded: ${apk.name} (${apk.length()} bytes)")
        return apk
    }

    private fun validateUpdateApk(apk: File) {
        val info = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: error("Downloaded file is not a valid APK.")
        require(info.packageName == packageName) {
            "Downloaded APK package was ${info.packageName}, expected $packageName."
        }
        val downloadedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        require(downloadedVersion > BuildConfig.VERSION_CODE.toLong()) {
            "Downloaded APK is not newer. Current=${BuildConfig.VERSION_CODE}, downloaded=$downloadedVersion."
        }
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
        if (release != null && !release.isReadyForCurrentBuild()) {
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

    private fun String.isShizuluManagerApkName(): Boolean {
        return endsWith(".apk", ignoreCase = true) &&
            startsWith("Shizulu-", ignoreCase = true) &&
            !contains("Tester", ignoreCase = true)
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

    private fun GithubRelease.isReadyForCurrentBuild(): Boolean {
        return updateAvailable && checkedAgainst.equals(BuildConfig.GIT_SHA, ignoreCase = true)
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
        return textButton(label, COLORS.primary, COLORS.onPrimary, 0, onClick)
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): TextView {
        return textButton(label, COLORS.surface, COLORS.ink, COLORS.outlineStrong, onClick)
    }

    private fun readableSecondaryButton(label: String, onClick: () -> Unit): TextView {
        return secondaryButton(label, onClick)
    }

    private fun compactButton(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val background = if (filled) COLORS.primary else COLORS.surfaceAlt
        val foreground = if (filled) COLORS.onPrimary else COLORS.ink
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
        chip.text = label
        chip.setTextColor(COLORS.ink)
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

    private fun String.shellQuote(): String {
        return "'${replace("'", "'\"'\"'")}'"
    }

    private fun String.isSafeShellToken(): Boolean {
        return matches(SAFE_SHELL_TOKEN_PATTERN)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private object COLORS {
        var palette = AccentTheme.DEFAULT.palette(AppearanceMode.LIGHT)
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
        val onPrimary get() = palette.onPrimary
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
        private const val REQUEST_NOTIFICATIONS = 46
        private const val KEY_LOGS = "logs"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_CUSTOM_PROFILES = "custom_profiles"
        private const val KEY_HIDDEN_BUILTIN_PROFILES = "hidden_builtin_profiles"
        private const val KEY_EXECUTION_MODE = "execution_mode"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
        private const val KEY_ACCENT_THEME = "accent_theme"
        private const val KEY_UPDATER_STATUS = "updater_status"
        private const val KEY_LATEST_RELEASE = "latest_release"
        private const val KEY_WIRELESS_ADB_KEEP_ALIVE = "wireless_adb_keep_alive"
        private const val KEY_SU_BRIDGE_ENABLED = "su_bridge_enabled"
        private const val KEY_ADB_PAIRING_CODE = "adb_pairing_code"
        private const val KEY_ADB_PAIR_PORT = "adb_pair_port"
        private const val KEY_ADB_CONNECT_PORT = "adb_connect_port"
        private const val MAX_LOG_LINES = 160
        private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/Glitch98777/shizulu/releases/latest"
        private const val GITHUB_COMPARE_URL = "https://api.github.com/repos/Glitch98777/shizulu/compare/%s...%s"
        private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private val SAFE_SHELL_TOKEN_PATTERN = Regex("^[A-Za-z0-9_./:-]+$")

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
    val apkUrl: String,
    val checkedAgainst: String? = null,
    val updateAvailable: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("tag", tag)
            put("name", name)
            put("commitSha", commitSha ?: "")
            put("apkName", apkName)
            put("apkUrl", apkUrl)
            put("checkedAgainst", checkedAgainst ?: "")
            put("updateAvailable", updateAvailable)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): GithubRelease {
            return GithubRelease(
                tag = obj.optString("tag"),
                name = obj.optString("name"),
                commitSha = obj.optString("commitSha").takeIf { it.isNotBlank() },
                apkName = obj.optString("apkName"),
                apkUrl = obj.optString("apkUrl"),
                checkedAgainst = obj.optString("checkedAgainst").takeIf { it.isNotBlank() },
                updateAvailable = obj.optBoolean("updateAvailable", false)
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
    val onPrimary: Int,
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
    DEFAULT("Default", 0xFFE5E7EB.toInt(), 0xFF273142.toInt()),
    BLUE("Blue", 0xFFDCEAFF.toInt(), 0xFF1E3A8A.toInt()),
    JADE("Jade", 0xFFDDF8EE.toInt(), 0xFF065F46.toInt()),
    VIOLET("Violet", 0xFFEDE7FF.toInt(), 0xFF5B21B6.toInt()),
    ROSE("Rose", 0xFFFFE4EC.toInt(), 0xFF9F1239.toInt());

    fun palette(mode: AppearanceMode): ThemePalette {
        val dark = mode == AppearanceMode.DARK
        val primary = if (dark) darkPrimary else lightPrimary
        val onPrimary = if (dark) 0xFFFFFFFF.toInt() else 0xFF111827.toInt()
        val darkBackground = 0xFF202124.toInt()
        val darkSurface = 0xFF2B2C30.toInt()
        val darkSurfaceAlt = 0xFF34363B.toInt()
        return ThemePalette(
            dark = dark,
            background = if (dark) darkBackground else 0xFFF7F9FC.toInt(),
            surface = if (dark) darkSurface else 0xFFFFFFFF.toInt(),
            surfaceAlt = if (dark) darkSurfaceAlt else 0xFFF1F5F9.toInt(),
            ink = if (dark) 0xFFFFFFFF.toInt() else 0xFF111827.toInt(),
            body = if (dark) 0xFFE8EAED.toInt() else 0xFF1F2937.toInt(),
            muted = if (dark) 0xFFC9CDD3.toInt() else 0xFF475569.toInt(),
            outline = if (dark) 0xFF33353A.toInt() else 0xFFE1E8F0.toInt(),
            outlineStrong = if (dark) 0xFF3E4046.toInt() else 0xFFCBD5E1.toInt(),
            primary = primary,
            onPrimary = onPrimary,
            primarySoft = if (dark) tint(primary, 0.32f, darkSurfaceAlt) else tint(primary, 0.48f, 0xFFFFFFFF.toInt()),
            secondary = if (dark) lighten(0xFF5B21B6.toInt(), 0.52f) else 0xFF5B21B6.toInt(),
            secondarySoft = if (dark) 0xFF3B344B.toInt() else 0xFFF1EAFF.toInt(),
            success = if (dark) 0xFF86EFAC.toInt() else 0xFF0E7A53.toInt(),
            successSoft = if (dark) 0xFF284237.toInt() else 0xFFE4F8EF.toInt(),
            warning = if (dark) 0xFFFDBA74.toInt() else 0xFF9A5B00.toInt(),
            warningSoft = if (dark) 0xFF4A3824.toInt() else 0xFFFFF1D6.toInt()
        )
    }

    companion object {
        fun from(value: String?): AccentTheme {
            return entries.firstOrNull { it.name == value } ?: DEFAULT
        }

        private fun tint(color: Int, amount: Float, base: Int): Int {
            val r = Color.red(base) + ((Color.red(color) - Color.red(base)) * amount).toInt()
            val g = Color.green(base) + ((Color.green(color) - Color.green(base)) * amount).toInt()
            val b = Color.blue(base) + ((Color.blue(color) - Color.blue(base)) * amount).toInt()
            return Color.rgb(r, g, b)
        }

        private fun lighten(color: Int, amount: Float): Int {
            return tint(color, 1f - amount, Color.WHITE)
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
        canvas.drawLine(w * 0.28f, h * 0.78f, w * 0.72f, h * 0.30f, paint)
        canvas.drawLine(w * 0.70f, h * 0.24f, w * 0.80f, h * 0.15f, paint)
        canvas.drawLine(w * 0.76f, h * 0.30f, w * 0.85f, h * 0.20f, paint)

        canvas.drawLine(w * 0.28f, h * 0.25f, w * 0.73f, h * 0.75f, paint)
        rect.set(w * 0.18f, h * 0.15f, w * 0.36f, h * 0.32f)
        canvas.drawRoundRect(rect, 3.5f, 3.5f, paint)
        canvas.drawCircle(w * 0.77f, h * 0.80f, w * 0.06f, paint)
    }
}
