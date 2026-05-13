package com.shizulu.manager

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
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
    private lateinit var grantButton: TextView
    private lateinit var dryRunButton: TextView
    private lateinit var moduleCount: TextView
    private lateinit var profilesList: LinearLayout
    private lateinit var moduleList: LinearLayout
    private var service: IShizuluService? = null
    private var dryRunEnabled = false

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
        tuneWindow()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        dryRunEnabled = settingsPrefs.getBoolean(KEY_DRY_RUN, false)
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
    }

    private fun tuneWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = COLORS.background
        window.navigationBarColor = COLORS.background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    private fun buildUi() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(COLORS.background)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        rootScroll.addView(root, LinearLayout.LayoutParams(-1, -2))

        root.addView(appHeader())
        root.addView(statusPanel(), spacedParams(top = 18))
        root.addView(primaryActions(), spacedParams(top = 14))
        root.addView(profilesHeader(), spacedParams(top = 26))

        profilesList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(profilesList, spacedParams(top = 10))

        root.addView(sectionHeader(), spacedParams(top = 26))

        moduleList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(moduleList, spacedParams(top = 10))
        root.addView(logsFooter(), spacedParams(top = 18))

        setContentView(rootScroll)
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
                    text = "ADB-privileged shizule manager"
                    textSize = 14f
                    setTextColor(COLORS.muted)
                    includeFontPadding = false
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
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

                addView(shizukuChip)
                addView(permissionChip, rowGapParams())
                addView(serviceChip, rowGapParams())
            })
        }
    }

    private fun primaryActions(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(primaryButton("Import .json") { openJsonPicker() }, LinearLayout.LayoutParams(0, dp(48), 1f))

            grantButton = secondaryButton("Grant Shizuku") { requestShizukuPermission() }
            addView(grantButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                leftMargin = dp(10)
            })
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
            setPadding(0, dp(8), 0, 0)

            addView(secondaryButton("Logs") { showLogs() }, LinearLayout.LayoutParams(-1, dp(48)))
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

            grantButton.text = if (permissionGranted) "Bind service" else "Grant Shizuku"
        }
    }

    private fun renderDryRun() {
        if (!::dryRunButton.isInitialized) return
        dryRunButton.text = if (dryRunEnabled) "Dry Run: On" else "Dry Run: Off"
        dryRunButton.setTextColor(if (dryRunEnabled) COLORS.warning else COLORS.primary)
    }

    private fun toggleDryRun() {
        dryRunEnabled = !dryRunEnabled
        settingsPrefs.edit().putBoolean(KEY_DRY_RUN, dryRunEnabled).apply()
        appendLog("Dry run ${if (dryRunEnabled) "enabled" else "disabled"}")
        renderDryRun()
        Toast.makeText(this, if (dryRunEnabled) "Dry run enabled" else "Dry run disabled", Toast.LENGTH_SHORT).show()
    }

    private fun refreshModules() {
        moduleList.removeAllViews()
        val shizules = store.list()
        moduleCount.text = shizules.size.toString()
        refreshProfiles(shizules)

        if (shizules.isEmpty()) {
            moduleList.addView(emptyState())
            return
        }

        shizules.forEachIndexed { index, shizule ->
            moduleList.addView(moduleView(shizule), spacedParams(top = if (index == 0) 0 else 12))
        }
    }

    private fun refreshProfiles(shizules: List<Shizule>) {
        profilesList.removeAllViews()
        PROFILES.forEachIndexed { index, profile ->
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

                addView(compactButton("Run", filled = missing == 0) { runProfile(profile) })
            })
        }
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
                text = buildString {
                    if (!shizule.isSigned) append("Unsigned shizule. Review commands before running.\n")
                    append(shizule.description.ifBlank { "No description provided." })
                }
                textSize = 14f
                setTextColor(if (shizule.isSigned) COLORS.body else COLORS.warning)
                setLineSpacing(0f, 1.08f)
                setPadding(0, dp(12), 0, dp(13))
            })

            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    shizule.actions.forEach { action ->
                        addView(compactButton(action.label, filled = true) { maybeRunAction(shizule, action) })
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
                if (shizule.isSigned) installTrusted(raw, shizule) else warnUnsignedInstall(raw, shizule)
            }
            .onFailure {
                appendLog("Install failed: ${it.message ?: "invalid shizule"}")
                Toast.makeText(this, it.message ?: "Invalid shizule", Toast.LENGTH_LONG).show()
            }
    }

    private fun warnUnsignedInstall(raw: String, shizule: Shizule) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Unsigned shizule")
            .setMessage("${shizule.name} has no signature metadata. Only install it if you trust where it came from.\n\nDry Run can preview its commands without changing the device.")
            .setPositiveButton("Install anyway") { _, _ -> installTrusted(raw, shizule) }
            .setNegativeButton("Cancel") { _, _ -> appendLog("Canceled unsigned shizule install: ${shizule.name}") }
            .show()
    }

    private fun installTrusted(raw: String, shizule: Shizule) {
        runCatching { store.install(raw) }
            .onSuccess {
                appendLog("Installed ${if (shizule.isSigned) "signed" else "unsigned"} shizule ${it.name} (${it.id})")
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

    private fun maybeRunAction(shizule: Shizule, action: ShizuleAction) {
        if (shizule.isSigned || dryRunEnabled) {
            runAction(shizule, action)
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Run unsigned shizule?")
            .setMessage("${shizule.name} is unsigned. Review the commands first or enable Dry Run to log them without executing.")
            .setPositiveButton("Run") { _, _ -> runAction(shizule, action) }
            .setNegativeButton("Cancel") { _, _ -> appendLog("Canceled unsigned action: ${shizule.name}/${action.label}") }
            .show()
    }

    private fun runAction(shizule: Shizule, action: ShizuleAction) {
        if (dryRunEnabled) {
            dryRunAction(shizule, action)
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
        const val background = 0xFFF7F9FC.toInt()
        const val surface = 0xFFFFFFFF.toInt()
        const val surfaceAlt = 0xFFF1F5F9.toInt()
        const val ink = 0xFF172033.toInt()
        const val body = 0xFF31415A.toInt()
        const val muted = 0xFF6B778C.toInt()
        const val outline = 0xFFE1E8F0.toInt()
        const val outlineStrong = 0xFFCBD5E1.toInt()
        const val primary = 0xFF3A7DFF.toInt()
        const val primarySoft = 0xFFE8F0FF.toInt()
        const val secondary = 0xFF7C3AED.toInt()
        const val secondarySoft = 0xFFF1EAFF.toInt()
        const val success = 0xFF0E7A53.toInt()
        const val successSoft = 0xFFE4F8EF.toInt()
        const val warning = 0xFF9A5B00.toInt()
        const val warningSoft = 0xFFFFF1D6.toInt()
    }

    companion object {
        private const val REQUEST_IMPORT = 42
        private const val REQUEST_SHIZUKU = 43
        private const val KEY_LOGS = "logs"
        private const val KEY_DRY_RUN = "dry_run"
        private const val MAX_LOG_LINES = 160

        private val PROFILES = listOf(
            ShizuluProfile(
                name = "Comfort Setup",
                description = "Fast animations plus display comfort.",
                steps = listOf(
                    ProfileStep("com.shizulu.sample.animation_tuner", "fast"),
                    ProfileStep("com.shizulu.sample.display_comfort_pack", "comfort")
                )
            ),
            ShizuluProfile(
                name = "Clean Pixel",
                description = "Light debloat and fast animations.",
                steps = listOf(
                    ProfileStep("com.shizulu.sample.light_debloat_manager", "apply"),
                    ProfileStep("com.shizulu.sample.animation_tuner", "fast")
                )
            ),
            ShizuluProfile(
                name = "Stock Restore",
                description = "Restore defaults for bundled shizules.",
                steps = listOf(
                    ProfileStep("com.shizulu.sample.light_debloat_manager", "restore"),
                    ProfileStep("com.shizulu.sample.display_comfort_pack", "restore"),
                    ProfileStep("com.shizulu.sample.animation_tuner", "normal")
                )
            )
        )
    }
}

data class ShizuluProfile(
    val name: String,
    val description: String,
    val steps: List<ProfileStep>
)

data class ProfileStep(
    val moduleId: String,
    val actionId: String
)
