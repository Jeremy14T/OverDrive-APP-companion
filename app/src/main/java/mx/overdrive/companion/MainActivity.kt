package mx.overdrive.companion

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ScrollView
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.MotionEvent
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** Companion manages saved entry points; all car features and authentication remain in OverDrive. */
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    }

    private lateinit var store: ConnectionStore
    private lateinit var setupPanel: View
    private lateinit var webPanel: View
    private lateinit var errorPanel: View
    private lateinit var webContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var providerGroup: RadioGroup
    private lateinit var providerPicker: View
    private var autoDetectedProvider: ConnectionUrl.Provider? = null
    private lateinit var progress: ProgressBar
    private lateinit var connectionHintButton: Button
    private lateinit var returnToPanelButton: Button
    private lateinit var tailscaleAdvice: View
    private lateinit var tailscaleHelpButton: Button
    private lateinit var errorTitle: TextView
    private lateinit var errorDescription: TextView
    private lateinit var openTailscaleButton: Button
    private lateinit var loadingState: View
    private lateinit var backHandler: OnBackPressedCallback
    private lateinit var manualForm: View
    private lateinit var firstRunPanel: View
    private lateinit var savedProfilesPanel: View
    private lateinit var profileList: LinearLayout
    private var editingId: String? = null
    private val probeExecutor = Executors.newFixedThreadPool(2)

    @Volatile private var current: ConnectionStore.Saved? = null
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var navigationTimeout: Runnable? = null
    private var navigationPending = false
    private var mainFrameFailed = false
    private var navigationFailed = false
    // Set only after opening the Tailscale app from the preflight warning.
    private var awaitingTailscaleReturn = false
    private var panelReady = false
    private var hideHintTask: Runnable? = null
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim() ?: return@registerForActivityResult
        val parsed = ConnectionUrl.parseInput(raw)
        if (parsed == null) {
            toast(getString(R.string.qr_invalid))
        } else {
            // A scanned QR only fills the form. User confirms before saving/switching.
            // If a saved connection is currently being edited, keep that edit context.
            if (manualForm.visibility != View.VISIBLE) openForm(null)
            urlInput.setText(parsed.url)
            urlInput.setSelection(parsed.url.length)
            // The URL watcher automatically detects the tunnel, just like manual paste.
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileChooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileChooser = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val request = pendingWebPermission
        pendingWebPermission = null
        if (request != null) {
            val needed = request.resources.filter {
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            val enough = needed.isNotEmpty() && needed.all { resource ->
                val permission = if (resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
                granted[permission] == true ||
                    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }
            if (enough && current?.let { ConnectionUrl.sameOrigin(it.url, request.origin.toString()) } == true) {
                request.grant(needed.toTypedArray())
            } else request.deny()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.surface)
        window.navigationBarColor = getColor(R.color.surface)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { root, insets ->
            // Preserve the verified v1.1 fix: do not forward handled system-bar insets to WebView.
            val handledTypes = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            val bars = insets.getInsets(handledTypes)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.Builder(insets).setInsets(handledTypes, Insets.NONE).build()
        }
        store = ConnectionStore(this)
        setupPanel = findViewById(R.id.setupPanel)
        webPanel = findViewById(R.id.webPanel)
        errorPanel = findViewById(R.id.errorPanel)
        webContainer = findViewById(R.id.webContainer)
        urlInput = findViewById(R.id.urlInput)
        nameInput = findViewById(R.id.connectionNameInput)
        providerGroup = findViewById(R.id.providerGroup)
        providerPicker = findViewById(R.id.providerPicker)
        progress = findViewById(R.id.loadingProgress)
        manualForm = findViewById(R.id.manualForm)
        firstRunPanel = findViewById(R.id.firstRunPanel)
        savedProfilesPanel = findViewById(R.id.savedProfilesPanel)
        profileList = findViewById(R.id.profileList)
        connectionHintButton = findViewById(R.id.changeButton)
        returnToPanelButton = findViewById(R.id.returnToPanelButton)
        tailscaleAdvice = findViewById(R.id.tailscaleAdvice)
        tailscaleHelpButton = findViewById(R.id.tailscaleHelpButton)
        errorTitle = findViewById(R.id.errorTitle)
        errorDescription = findViewById(R.id.errorDescription)
        openTailscaleButton = findViewById(R.id.openTailscaleButton)
        loadingState = findViewById(R.id.loadingState)
        tailscaleHelpButton.setOnClickListener { openTailscale() }
        openTailscaleButton.setOnClickListener { openTailscale() }
        providerGroup.setOnCheckedChangeListener { _, _ ->
            refreshTailscaleAdvice()
        }

        backHandler = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    setupPanel.visibility == View.VISIBLE && manualForm.visibility == View.VISIBLE -> {
                        if (store.all().isEmpty()) finish() else closeForm()
                    }
                    setupPanel.visibility == View.VISIBLE && canReturnToPanel() -> returnToPanel()
                    webPanel.visibility == View.VISIBLE && webView?.canGoBack() == true -> webView?.goBack()
                    webPanel.visibility == View.VISIBLE -> showBackMenu()
                    errorPanel.visibility == View.VISIBLE -> showSetup()
                    else -> finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backHandler)

        findViewById<View>(R.id.scanButton).setOnClickListener { launchQr() }
        findViewById<Button>(R.id.addQrButton).setOnClickListener { launchQr() }
        findViewById<Button>(R.id.addManualButton).setOnClickListener { openForm(null) }
        findViewById<Button>(R.id.cancelEditButton).setOnClickListener { closeForm() }
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshProviderDetection(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<Button>(R.id.connectButton).setOnClickListener { saveAndConnect() }
        findViewById<Button>(R.id.useLastButton).setOnClickListener {
            store.useLastOnStartup()
            renderProfiles()
            toast("Se abrirá la última conexión utilizada")
        }
        findViewById<Button>(R.id.retryButton).setOnClickListener { loadPanel() }
        findViewById<Button>(R.id.editButton).setOnClickListener { showSetup() }
        connectionHintButton.setOnClickListener { showSetup() }
        returnToPanelButton.setOnClickListener { returnToPanel() }

        current = store.startup()
        if (current != null) loadPanel() else showSetup()
    }

    private fun selectedProvider(): ConnectionUrl.Provider? = when (providerGroup.checkedRadioButtonId) {
        R.id.cloudflareOption -> ConnectionUrl.Provider.CLOUDFLARE
        R.id.zrokOption -> ConnectionUrl.Provider.ZROK
        R.id.tailscaleOption -> ConnectionUrl.Provider.TAILSCALE
        else -> null // Do not silently label an unknown/custom URL as Cloudflare.
    }

    /** For recognized tunnel addresses the provider is chosen automatically and locked.
     * Custom domains and ambiguous LAN IPs cannot reveal their provider; allow manual choice.
     */
    private fun refreshProviderDetection(raw: String) {
        val detected = ConnectionUrl.suggestProvider(raw)
        val previousAuto = autoDetectedProvider
        autoDetectedProvider = detected
        val info = findViewById<TextView>(R.id.providerInfo)
        if (raw.isBlank()) {
            providerPicker.visibility = View.GONE
            providerGroup.clearCheck()
            info.text = "El tipo de conexión se identificará automáticamente."
            info.setTextColor(getColor(R.color.muted))
            info.visibility = View.GONE
            refreshTailscaleAdvice()
            return
        }
        if (detected != null) {
            chooseProvider(detected)
            providerPicker.visibility = View.GONE
            info.text = "✓ Tipo detectado automáticamente: ${detected.displayName}"
            info.setTextColor(getColor(R.color.accent))
            info.visibility = View.VISIBLE
            refreshTailscaleAdvice()
            return
        }
        if (previousAuto != null) providerGroup.clearCheck()
        providerPicker.visibility = View.VISIBLE
        val valid = ConnectionUrl.parseInput(raw) != null
        info.text = if (valid)
            "Esta dirección no identifica un proveedor con certeza. Selecciónalo una vez para guardarla."
            else "Introduce una dirección HTTP privada o HTTPS válida."
        info.setTextColor(getColor(R.color.muted))
        info.visibility = View.VISIBLE
        refreshTailscaleAdvice()
    }

    private fun refreshTailscaleAdvice() {
        if (!::tailscaleAdvice.isInitialized) return
        val isTailscale = autoDetectedProvider == ConnectionUrl.Provider.TAILSCALE ||
            (autoDetectedProvider == null && selectedProvider() == ConnectionUrl.Provider.TAILSCALE)
        tailscaleAdvice.visibility = if (isTailscale) View.VISIBLE else View.GONE
        if (isTailscale) updateTailscaleActionText(tailscaleHelpButton)
    }

    private fun updateTailscaleActionText(button: Button) {
        button.setText(if (tailscaleLaunchIntent() != null)
            R.string.open_tailscale else R.string.install_tailscale)
    }

    private fun tailscaleLaunchIntent(): Intent? =
        packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)

    private fun openTailscale() {
        val intent = tailscaleLaunchIntent() ?: Intent(Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$TAILSCALE_PACKAGE"))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE")))
            } catch (_: Exception) {
                toast(getString(R.string.tailscale_open_failed))
            }
        }
    }

    private fun chooseProvider(provider: ConnectionUrl.Provider) {
        providerGroup.check(when (provider) {
            ConnectionUrl.Provider.CLOUDFLARE -> R.id.cloudflareOption
            ConnectionUrl.Provider.ZROK -> R.id.zrokOption
            ConnectionUrl.Provider.TAILSCALE -> R.id.tailscaleOption
        })
    }

    private fun canReturnToPanel(): Boolean = panelReady && webView != null && current != null

    private fun showBackMenu() {
        AlertDialog.Builder(this)
            .setTitle(R.string.back_menu_title)
            .setMessage(R.string.back_menu_message)
            .setPositiveButton("Mis conexiones") { _, _ -> showSetup() }
            .setNeutralButton(R.string.exit_app) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun launchQr() {
        qrScanner.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.qr_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
            setCaptureActivity(PortraitQrCaptureActivity::class.java)
        })
    }

    private fun showSetup() {
        // Keep WebView, cookies and history intact until the user actually selects a different URL.
        stopTimeout()
        manualForm.visibility = View.GONE
        editingId = null
        renderProfiles()
        // The first run uses one visible card: QR + URL + automatic provider detection.
        if (store.all().isEmpty()) openForm(null)
        returnToPanelButton.visibility = if (canReturnToPanel()) View.VISIBLE else View.GONE
        showOnly(setupPanel)
        (setupPanel as ScrollView).scrollTo(0, 0)
    }

    private fun renderProfiles() {
        val profiles = store.all()
        firstRunPanel.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        savedProfilesPanel.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
        profileList.removeAllViews()
        val favoriteId = store.favoriteId()
        val lastId = store.lastId()
        val pinned = store.isFavoritePinned()
        findViewById<TextView>(R.id.startupModeLabel).text = when {
            pinned && favoriteId != null ->
                "Al iniciar se abrirá la favorita. Puedes cambiarla tocando la estrella de otra conexión."
            favoriteId == null ->
                "Sin favorita: al iniciar se abrirá la última utilizada. Toca una estrella para elegir favorita."
            else -> "Inicio rápido: se abre la última utilizada. La estrella indica tu favorita inicial."
        }
        findViewById<Button>(R.id.useLastButton).visibility = if (pinned) View.VISIBLE else View.GONE
        profiles.forEach { saved ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                setBackgroundResource(R.drawable.card_bg)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            }
            // Keep favorite control in the upper-right corner of its own connection card.
            // An ImageButton provides a 48dp touch target without an extra action-row button.
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(TextView(this).apply {
                text = saved.label
                setTextColor(getColor(R.color.white))
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            val isFavorite = saved.id == favoriteId
            heading.addView(ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginStart = dp(6)
                }
                setImageResource(if (isFavorite) R.drawable.favorite_star_filled
                    else R.drawable.favorite_star_outline)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                // Native ripple feedback; no permanent visual button frame.
                val ripple = android.util.TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                    && ripple.resourceId != 0) {
                    setBackgroundResource(ripple.resourceId)
                } else setBackgroundColor(android.graphics.Color.TRANSPARENT)
                contentDescription = if (isFavorite) "Quitar ${saved.label} de favoritas"
                    else "Marcar ${saved.label} como favorita"
                setOnClickListener {
                    if (isFavorite) {
                        store.clearFavorite(saved.id)
                        toast("Se abrirá la última conexión utilizada")
                    } else {
                        store.setFavorite(saved.id)
                        toast("${saved.label}: favorita para siguientes aperturas")
                    }
                    // Refresh all cards so that exactly one star can remain filled.
                    renderProfiles()
                }
            })
            card.addView(heading)
            val badges = buildList {
                if (!saved.label.equals(saved.provider.displayName, ignoreCase = true)) {
                    add(saved.provider.displayName)
                }
                if (saved.id == lastId) add("Última usada")
                if (saved.id == current?.id && canReturnToPanel()) add("Abierta")
            }.joinToString("  ·  ")
            if (badges.isNotEmpty()) {
                card.addView(TextView(this).apply {
                    text = badges
                    setTextColor(getColor(R.color.accent)); textSize = 12f
                    setPadding(0, 0, 0, dp(3))
                })
            }
            card.addView(TextView(this).apply {
                // Keep the full URL visible for human confirmation, but do not log it.
                text = saved.url
                setTextColor(getColor(R.color.muted)); textSize = 12f
                setTextIsSelectable(true)
            })
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, 0)
            }
            buttons.addView(actionButton("Abrir", true) { useProfile(saved) })
            buttons.addView(actionButton("Probar", false) { testProfile(saved, card) })
            card.addView(buttons)
            card.addView(actionButton("Editar dirección", false) { openForm(saved) }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) }
            })
            profileList.addView(card)
        }
    }

    private fun actionButton(title: String, primary: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 12f
            setTextColor(getColor(if (primary) R.color.surface else R.color.white))
            setBackgroundResource(if (primary) R.drawable.accent_button else R.drawable.secondary_button)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) }
            setOnClickListener { action() }
        }

    private fun testProfile(saved: ConnectionStore.Saved, card: LinearLayout) {
        val status = TextView(this).apply {
            text = "Comprobando dirección…"
            setTextColor(getColor(R.color.muted)); textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(status)
        probeExecutor.execute {
            val result = ConnectionProbe.check(saved)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = when (result) {
                    is ConnectionProbe.Result.Answered -> when (result.status) {
                        in 200..399 -> "Responde HTTP ${result.status}. No confirma que sea OverDrive ni que tu sesión esté autenticada."
                        401, 403 -> "Responde HTTP ${result.status}: acceso restringido. Podría requerir autenticación."
                        405, 501 -> "Responde HTTP ${result.status}: no permite esta prueba rápida (HEAD). Intenta abrirlo."
                        in 500..599 -> "Responde HTTP ${result.status}: posible fallo del servidor o túnel."
                        else -> "Responde HTTP ${result.status}: revisa que la dirección sea correcta."
                    }
                    is ConnectionProbe.Result.Failed -> result.reason
                }
                val healthyResponse = result is ConnectionProbe.Result.Answered && result.status in 200..399
                status.setTextColor(getColor(if (healthyResponse) R.color.accent else R.color.danger))
            }
        }
    }

    private fun openForm(existing: ConnectionStore.Saved?) {
        val firstRun = store.all().isEmpty()
        editingId = existing?.id
        savedProfilesPanel.visibility = View.GONE
        manualForm.visibility = View.VISIBLE
        backHandler.isEnabled = true
        findViewById<TextView>(R.id.formTitle).text = when {
            firstRun -> "Tu primera conexión"
            existing != null -> "Editar conexión guardada"
            else -> "Agregar conexión"
        }
        findViewById<Button>(R.id.cancelEditButton).visibility =
            if (firstRun) View.GONE else View.VISIBLE
        autoDetectedProvider = null
        providerGroup.clearCheck()
        nameInput.setText(existing?.label ?: "")
        urlInput.setText(existing?.url ?: "") // TextWatcher derives provider automatically.
        if (existing != null && ConnectionUrl.suggestProvider(existing.url) == null) {
            // Preserve the correct tunnel type for a saved custom domain.
            chooseProvider(existing.provider)
        }
        refreshTailscaleAdvice()
        if (!firstRun) {
            (setupPanel as ScrollView).post {
                (setupPanel as ScrollView).smoothScrollTo(0, manualForm.top)
            }
        }
    }

    private fun closeForm() {
        manualForm.visibility = View.GONE
        editingId = null
        autoDetectedProvider = null
        savedProfilesPanel.visibility = View.VISIBLE
        backHandler.isEnabled = canReturnToPanel()
    }

    private fun saveAndConnect() {
        val parsed = ConnectionUrl.parseInput(urlInput.text.toString())
        if (parsed == null) {
            urlInput.error = getString(R.string.invalid_url)
            return
        }
        val provider = parsed.detectedProvider ?: selectedProvider()
        if (provider == null) {
            providerPicker.visibility = View.VISIBLE
            toast("Selecciona Cloudflare, Zrok o Tailscale para esta dirección")
            return
        }
        val validUrl = ConnectionUrl.validate(parsed.url, provider)
        if (validUrl == null) {
            urlInput.error = getString(R.string.invalid_url)
            return
        }
        val saved = store.upsert(provider, validUrl, nameInput.text.toString(), editingId)
        editingId = null
        manualForm.visibility = View.GONE
        useProfile(saved)
    }

    private fun useProfile(saved: ConnectionStore.Saved) {
        val previous = current
        store.markUsed(saved.id)
        if (previous?.url == saved.url && previous.provider == saved.provider && canReturnToPanel()) {
            current = saved
            returnToPanel()
            return
        }
        if (previous?.url != saved.url || previous.provider != saved.provider) {
            // Different web origin may need the original OverDrive login. Do not clear cookies.
            resetWebView()
        }
        current = saved
        loadPanel()
    }

    private fun returnToPanel() {
        if (!canReturnToPanel()) return
        showOnly(webPanel) // No reload, no cookie deletion, no forced login.
    }

    private fun showOnly(panel: View) {
        hideConnectionHint()
        setupPanel.visibility = if (panel === setupPanel) View.VISIBLE else View.GONE
        webPanel.visibility = if (panel === webPanel) View.VISIBLE else View.GONE
        errorPanel.visibility = if (panel === errorPanel) View.VISIBLE else View.GONE
        backHandler.isEnabled = panel !== setupPanel || canReturnToPanel() || manualForm.visibility == View.VISIBLE
    }

    private fun loadPanel(skipVpnAdvisory: Boolean = false) {
        val saved = current ?: run { showSetup(); return }
        // A failed main-frame WebView must be reset BEFORE a preflight dialog
        // changes panels; otherwise the retry could reuse a timed-out browser.
        if (errorPanel.visibility == View.VISIBLE) resetWebView()
        if (!skipVpnAdvisory && TailscalePreflightPolicy.shouldWarn(
                saved.provider, detectActiveVpn())) {
            // Advisory only: Android cannot prove which VPN is active, and some
            // private routes work without appearing as the phone's active VPN.
            showSetup()
            AlertDialog.Builder(this)
                .setTitle(R.string.tailscale_vpn_notice_title)
                .setMessage(R.string.tailscale_vpn_notice_message)
                .setPositiveButton(R.string.open_tailscale) { _, _ ->
                    awaitingTailscaleReturn = true
                    openTailscale()
                }
                .setNeutralButton(R.string.connect_anyway) { _, _ ->
                    awaitingTailscaleReturn = false
                    loadPanel(skipVpnAdvisory = true)
                }
                .setNegativeButton(R.string.my_connections) { _, _ ->
                    awaitingTailscaleReturn = false
                }
                .show()
            return
        }
        awaitingTailscaleReturn = false
        // Preserve CookieManager and all saved profiles; a failed view was
        // already replaced above, before showing any VPN advice.
        panelReady = false
        stopTimeout()
        mainFrameFailed = false
        navigationFailed = false
        ensureWebView()
        showOnly(webPanel)
        // Start the deadline BEFORE loadUrl: WebView may never call onPageStarted on bad networks.
        startTimeout()
        webView?.loadUrl(saved.url)
    }

    /** null means the OS cannot provide a reliable answer; proceed with normal loading. */
    private fun detectActiveVpn(): Boolean? {
        return try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = manager.activeNetwork
            if (active == null) false
            else manager.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun showError() {
        if (errorPanel.visibility == View.VISIBLE) return
        navigationFailed = true
        mainFrameFailed = true
        panelReady = false
        stopTimeout()
        webView?.stopLoading()
        val tailscale = ConnectionFailurePolicy.showTailscaleHelp(current?.provider)
        errorTitle.setText(if (tailscale) R.string.tailscale_error_title else R.string.connection_error_title)
        errorDescription.setText(if (tailscale) R.string.tailscale_error_text else R.string.connection_error_text)
        openTailscaleButton.visibility = if (tailscale) View.VISIBLE else View.GONE
        if (tailscale) updateTailscaleActionText(openTailscaleButton)
        showOnly(errorPanel)
    }

    private fun resetWebView() {
        panelReady = false
        stopTimeout()
        pendingWebPermission?.deny()
        pendingWebPermission = null
        fileChooser?.onReceiveValue(null)
        fileChooser = null
        webView?.let { browser ->
            browser.stopLoading()
            webContainer.removeView(browser)
            browser.destroy()
        }
        webView = null
    }

    private fun onMainPageReady(url: String) {
        if (!navigationPending || mainFrameFailed || navigationFailed ||
            webPanel.visibility != View.VISIBLE) return
        val saved = current ?: return
        if (!ConnectionUrl.sameOrigin(saved.url, url)) return
        stopTimeout()
        panelReady = true
        if (!store.hasShownConnectionHint()) {
            store.markConnectionHintShown()
            connectionHintButton.visibility = View.VISIBLE
            hideHintTask = Runnable { hideConnectionHint() }
                .also { handler.postDelayed(it, 5_000L) }
        }
    }

    private fun hideConnectionHint() {
        hideHintTask?.let { handler.removeCallbacks(it) }
        hideHintTask = null
        if (::connectionHintButton.isInitialized) connectionHintButton.visibility = View.GONE
    }

    private fun startTimeout() {
        if (navigationPending) return // No resetting deadline on redirects or repeated callbacks.
        navigationPending = true
        mainFrameFailed = false
        navigationFailed = false
        progress.visibility = View.VISIBLE
        if (!panelReady) loadingState.visibility = View.VISIBLE
        navigationTimeout = Runnable {
            if (navigationPending && webPanel.visibility == View.VISIBLE) showError()
        }.also { handler.postDelayed(it, ConnectionFailurePolicy.NAVIGATION_DEADLINE_MS) }
    }

    private fun stopTimeout() {
        navigationPending = false
        navigationTimeout?.let { handler.removeCallbacks(it) }
        navigationTimeout = null
        if (::progress.isInitialized) progress.visibility = View.GONE
        if (::loadingState.isInitialized) loadingState.visibility = View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun ensureWebView() {
        if (webView != null) return
        val browser = WebView(this)
        webView = browser
        webContainer.addView(browser, FrameLayout.LayoutParams(-1, -1))
        browser.setBackgroundColor(getColor(R.color.surface))
        browser.isVerticalScrollBarEnabled = false
        browser.isHorizontalScrollBarEnabled = false
        // Interacting with the original dashboard (including its drawer) dismisses the hint.
        browser.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) hideConnectionHint()
            false // Never consume touches intended for OverDrive.
        }
        browser.settings.apply {
            javaScriptEnabled = true  // Required by OverDrive's original frontend.
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true  // User-selected attachments only; never file:// pages.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // Some login flows may require 3rd-party cookies. Leave off by default for privacy.
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        WebView.setWebContentsDebuggingEnabled(false)

        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val destination = request.url.toString()
                val saved = current ?: return true
                if (ConnectionUrl.sameOrigin(saved.url, destination)) return false
                // Never follow cross-origin redirects silently. User-clicked external HTTPS links
                // may open in the phone's browser; internal OverDrive routes remain inside the app.
                if (request.hasGesture() && destination.startsWith("https://", ignoreCase = true)) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        })
                    } catch (_: Exception) { toast("No se pudo abrir el enlace externo") }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val saved = current ?: return blocked()
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                // Allow WebView-managed blob/data pages, not dangerous file/content schemes.
                if (scheme == "blob" || scheme == "data") return null
                if (!ConnectionUrl.allowedResource(saved.url, uri.toString())) return blocked()
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (view === webView && webPanel.visibility == View.VISIBLE && !navigationPending &&
                    current?.let { ConnectionUrl.sameOrigin(it.url, url) } == true) {
                    // Subsequent full-page navigation gets its own deadline as well.
                    startTimeout()
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (view === webView) onMainPageReady(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view === webView && request.isForMainFrame && webPanel.visibility == View.VISIBLE) {
                    mainFrameFailed = true
                    showError()
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                // 401/403 may be the original login page. 5xx on the main page is unavailable.
                if (view === webView && request.isForMainFrame && (errorResponse.statusCode >= 500 ||
                    errorResponse.statusCode == 404 || errorResponse.statusCode == 410) &&
                    webPanel.visibility == View.VISIBLE) {
                    mainFrameFailed = true
                    showError()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Never bypass invalid TLS certificates. A subresource TLS problem should not
                // replace a healthy main page; main-frame failure arrives via onReceivedError
                // or the navigation watchdog.
                handler.cancel()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                panelReady = false
                stopTimeout()
                webContainer.removeView(view)
                view.destroy()
                webView = null
                showError()
                return true
            }
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (navigationPending) {
                    progress.isIndeterminate = false
                    progress.progress = newProgress
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooser?.onReceiveValue(null)
                fileChooser = filePathCallback
                return try {
                    filePicker.launch(fileChooserParams.createIntent())
                    true
                } catch (_: Exception) {
                    fileChooser?.onReceiveValue(null)
                    fileChooser = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val saved = current
                    val resources = request.resources
                    if (saved == null || resources.isEmpty() || webPanel.visibility != View.VISIBLE ||
                        !ConnectionUrl.sameOrigin(saved.url, request.origin.toString()) ||
                        resources.any {
                            it != PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                                it != PermissionRequest.RESOURCE_VIDEO_CAPTURE
                        }
                    ) {
                        request.deny()
                        return@runOnUiThread
                    }
                    val needed = resources.map {
                        if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                            Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
                    }.distinct()
                    val notGranted = needed.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (notGranted.isEmpty()) request.grant(resources) else {
                        pendingWebPermission?.deny()
                        pendingWebPermission = request
                        permissionLauncher.launch(notGranted.toTypedArray())
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                runOnUiThread {
                    if (pendingWebPermission === request) pendingWebPermission = null
                }
            }
        }
        browser.setDownloadListener(DownloadListener { url, agent, disposition, mime, _ ->
            val saved = current ?: return@DownloadListener
            if (!ConnectionUrl.allowedResource(saved.url, url)) {
                toast("Descarga bloqueada por seguridad")
                return@DownloadListener
            }
            try {
                val download = DownloadManager.Request(Uri.parse(url))
                    .setTitle(URLUtil.guessFileName(url, disposition, mime))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, disposition, mime)
                    )
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) download.addRequestHeader("Cookie", cookies)
                if (!agent.isNullOrEmpty()) download.addRequestHeader("User-Agent", agent)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(download)
                toast("Descarga iniciada")
            } catch (_: Exception) {
                toast("No fue posible descargar el archivo")
            }
        })
    }

    private fun blocked(): WebResourceResponse = WebResourceResponse(
        "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))
    )

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Persist OverDrive's regular WebView cookies when Companion leaves the foreground.
        // Do not copy credentials/tokens, and do not alter OverDrive's authentication rules.
        // flush() writes cookies to the WebView cookie store; it does not make
        // session-only credentials persistent if OverDrive deliberately expires them.
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        // Coming back from Tailscale: re-check once. Only automatically retry if
        // Android now reports an active VPN; never loop or auto-open a failed URL.
        if (awaitingTailscaleReturn) {
            awaitingTailscaleReturn = false
            if (TailscalePreflightPolicy.shouldAutoRetry(current?.provider, detectActiveVpn())) {
                loadPanel()
            }
        }
    }

    override fun onDestroy() {
        probeExecutor.shutdownNow()
        hideConnectionHint()
        stopTimeout()
        pendingWebPermission?.deny()
        pendingWebPermission = null
        fileChooser?.onReceiveValue(null)
        fileChooser = null
        webView?.let { browser ->
            webContainer.removeView(browser)
            browser.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
