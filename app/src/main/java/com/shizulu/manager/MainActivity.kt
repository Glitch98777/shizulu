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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var moduleSummaryText: TextView
    private lateinit var profileSummaryText: TextView
    private lateinit var modeSummaryText: TextView
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
        if (requestCode == REQUEST_BACKUP_CREATE && resultCode == RESULT_OK) {
            data?.data?.let(::writeBackupToUri)
        }
        if (requestCode == REQUEST_BACKUP_OPEN && resultCode == RESULT_OK) {
            data?.data?.let(::restoreBackupFromUri)
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
        root.addView(sectionTitle("Dashboard"), spacedParams(top = 22))
        root.addView(statusPanel(), spacedParams(top = 10))
        root.addView(summaryStrip(), spacedParams(top = 12))
        root.addView(sectionTitle("Quick Actions"), spacedParams(top = 24))
        root.addView(primaryActions(), spacedParams(top = 10))
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
        root.addView(sectionTitle("Tools"), spacedParams(top = 26))
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
                    text = "Rootless modules powered by Shizuku"
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

                addView(shizukuChip)
                addView(permissionChip, rowGapParams())
                addView(serviceChip, rowGapParams())
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
                text = "Install JSON modules, grant Shizuku, then run actions from Profiles or Installed shizules."
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
        moduleList.removeAllViews()
        val shizules = store.list()
        moduleCount.text = shizules.size.toString()
        if (::moduleSummaryText.isInitialized) {
            moduleSummaryText.text = "${shizules.size}\nModules"
        }
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
                .apply()
            dryRunEnabled = settingsPrefs.getBoolean(KEY_DRY_RUN, false)

            val restoredLogs = backup.optString("logs", "")
            if (restoredLogs.isNotBlank()) logPrefs.edit().putString(KEY_LOGS, restoredLogs).apply()
            appendLog("Backup restored: $installed shizule(s), ${customProfiles.length()} custom profile(s)")
            renderDryRun()
            refreshModules()
            Toast.makeText(this, "Backup restored", Toast.LENGTH_SHORT).show()
        }
            .onFailure {
                appendLog("Restore failed: ${it.message ?: it.javaClass.simpleName}")
                Toast.makeText(this, it.message ?: "Restore failed", Toast.LENGTH_LONG).show()
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
        private const val REQUEST_BACKUP_CREATE = 44
        private const val REQUEST_BACKUP_OPEN = 45
        private const val KEY_LOGS = "logs"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_CUSTOM_PROFILES = "custom_profiles"
        private const val MAX_LOG_LINES = 160

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
