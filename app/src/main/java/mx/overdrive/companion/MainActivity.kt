package mx.overdrive.companion

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var store: ConnectionStore
    private lateinit var setupPanel: View
    private lateinit var webPanel: View
    private lateinit var errorPanel: View
    private lateinit var webContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var providerGroup: RadioGroup
    private lateinit var progress: ProgressBar
    private lateinit var connectionHintButton: Button
    private lateinit var returnToPanelButton: Button
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
    private var panelReady = false
    private var hideHintTask: Runnable? = null
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim() ?: return@registerForActivityResult
        val suggested = ConnectionUrl.suggestProvider(raw)
        val chosen = suggested ?: if (ConnectionUrl.validate(raw, ConnectionUrl.Provider.TAILSCALE) != null &&
            raw.startsWith("http://", ignoreCase = true)) ConnectionUrl.Provider.TAILSCALE
            else selectedProvider()
        val valid = ConnectionUrl.validate(raw, chosen)
        if (valid == null) {
            toast(getString(R.string.qr_invalid))
        } else {
            openForm(null)
            chooseProvider(chosen)
            urlInput.setText(valid)
            urlInput.setSelection(valid.length)
            findViewById<TextView>(R.id.providerInfo).text = if (suggested != null)
                "Proveedor sugerido: ${suggested.displayName}. Confirma la dirección antes de guardarla."
                else "No se identificó el proveedor con certeza. Selecciónalo y confirma la dirección."
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
        progress = findViewById(R.id.loadingProgress)
        manualForm = findViewById(R.id.manualForm)
        firstRunPanel = findViewById(R.id.firstRunPanel)
        savedProfilesPanel = findViewById(R.id.savedProfilesPanel)
        profileList = findViewById(R.id.profileList)
        connectionHintButton = findViewById(R.id.changeButton)
        returnToPanelButton = findViewById(R.id.returnToPanelButton)

        backHandler = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    setupPanel.visibility == View.VISIBLE && manualForm.visibility == View.VISIBLE -> {
                        manualForm.visibility = View.GONE
                        editingId = null
                        backHandler.isEnabled = canReturnToPanel()
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

        findViewById<Button>(R.id.scanButton).setOnClickListener { launchQr() }
        findViewById<Button>(R.id.addQrButton).setOnClickListener { launchQr() }
        findViewById<Button>(R.id.manualFirstButton).setOnClickListener { openForm(null) }
        findViewById<Button>(R.id.addManualButton).setOnClickListener { openForm(null) }
        findViewById<Button>(R.id.cancelEditButton).setOnClickListener {
            manualForm.visibility = View.GONE
            editingId = null
            backHandler.isEnabled = canReturnToPanel()
        }
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

    private fun selectedProvider(): ConnectionUrl.Provider = when (providerGroup.checkedRadioButtonId) {
        R.id.zrokOption -> ConnectionUrl.Provider.ZROK
        R.id.tailscaleOption -> ConnectionUrl.Provider.TAILSCALE
        else -> ConnectionUrl.Provider.CLOUDFLARE
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
        returnToPanelButton.visibility = if (canReturnToPanel()) View.VISIBLE else View.GONE
        showOnly(setupPanel)
    }

    private fun renderProfiles() {
        val profiles = store.all()
        firstRunPanel.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        savedProfilesPanel.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
        profileList.removeAllViews()
        val favoriteId = store.favoriteId()
        val lastId = store.lastId()
        val pinned = store.isFavoritePinned()
        findViewById<TextView>(R.id.startupModeLabel).text = if (pinned)
            "Al iniciar se abrirá tu favorita marcada. Puedes cambiarla tocando ★ en otra conexión."
            else "Inicio rápido: se abre la última utilizada. La primera que guardaste quedó como favorita inicial."
        findViewById<Button>(R.id.useLastButton).visibility = if (pinned) View.VISIBLE else View.GONE
        profiles.forEach { saved ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                setBackgroundResource(R.drawable.card_bg)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            }
            val badges = buildList {
                add(saved.provider.displayName)
                if (saved.id == favoriteId) add("★ Favorita")
                if (saved.id == lastId) add("Última usada")
                if (saved.id == current?.id && canReturnToPanel()) add("Abierta")
            }.joinToString("  ·  ")
            card.addView(TextView(this).apply {
                text = saved.label
                setTextColor(getColor(R.color.white)); textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = badges
                setTextColor(getColor(R.color.accent)); textSize = 12f
                setPadding(0, dp(4), 0, dp(3))
            })
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
            buttons.addView(actionButton(if (saved.id == favoriteId) "★" else "☆", false) {
                store.setFavorite(saved.id)
                renderProfiles()
                toast("${saved.label}: favorita para siguientes aperturas")
            }.apply { contentDescription = "Elegir ${saved.label} como favorita al iniciar" })
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
        editingId = existing?.id
        manualForm.visibility = View.VISIBLE
        backHandler.isEnabled = true
        findViewById<TextView>(R.id.formTitle).text = if (existing == null)
            "Confirmar nueva conexión" else "Editar conexión guardada"
        nameInput.setText(existing?.label ?: "")
        urlInput.setText(existing?.url ?: "")
        chooseProvider(existing?.provider ?: ConnectionUrl.Provider.CLOUDFLARE)
        findViewById<TextView>(R.id.providerInfo).text =
            "Revisa la dirección. HTTP se admite únicamente con IP privada para Tailscale."
        (setupPanel as ScrollView).post {
            (setupPanel as ScrollView).smoothScrollTo(0, manualForm.top)
        }
    }

    private fun saveAndConnect() {
        val provider = selectedProvider()
        val url = ConnectionUrl.validate(urlInput.text.toString(), provider)
        if (url == null) {
            urlInput.error = getString(R.string.invalid_url)
            return
        }
        val saved = store.upsert(provider, url, nameInput.text.toString(), editingId)
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

    private fun loadPanel() {
        val saved = current ?: run { showSetup(); return }
        panelReady = false
        ensureWebView()
        showOnly(webPanel)
        webView?.loadUrl(saved.url)
    }

    private fun showError() {
        panelReady = false
        stopTimeout()
        webView?.stopLoading()
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
        stopTimeout()
        if (webPanel.visibility != View.VISIBLE) return
        val saved = current ?: return
        if (!ConnectionUrl.sameOrigin(saved.url, url)) return
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
        stopTimeout()
        navigationPending = true
        progress.visibility = View.VISIBLE
        navigationTimeout = Runnable { if (navigationPending && webPanel.visibility == View.VISIBLE) showError() }
            .also { handler.postDelayed(it, 30_000L) }
    }

    private fun stopTimeout() {
        navigationPending = false
        navigationTimeout?.let { handler.removeCallbacks(it) }
        navigationTimeout = null
        if (::progress.isInitialized) progress.visibility = View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun ensureWebView() {
        if (webView != null) return
        val browser = WebView(this)
        webView = browser
        webContainer.addView(browser, FrameLayout.LayoutParams(-1, -1))
        browser.setBackgroundColor(Color.WHITE)
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
                if (webPanel.visibility == View.VISIBLE) startTimeout()
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                onMainPageReady(url) // No waiting for video or WebSockets.
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Fallback if this WebView version does not report a visible commit.
                if (navigationPending && view.progress == 100) onMainPageReady(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && webPanel.visibility == View.VISIBLE) showError()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                // 401/403 may be the original login page. 5xx on the main page is unavailable.
                if (request.isForMainFrame && (errorResponse.statusCode >= 500 ||
                    errorResponse.statusCode == 404 || errorResponse.statusCode == 410) &&
                    webPanel.visibility == View.VISIBLE) showError()
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
                showOnly(errorPanel)
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

    override fun onResume() {
        super.onResume()
        webView?.onResume()
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
