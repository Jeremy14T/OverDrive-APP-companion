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
import android.view.View
import android.view.MotionEvent
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

/** A thin shell around the vehicle's existing web dashboard; no custom car endpoints. */
class MainActivity : ComponentActivity() {
    private lateinit var store: ConnectionStore
    private lateinit var setupPanel: View
    private lateinit var webPanel: View
    private lateinit var errorPanel: View
    private lateinit var webContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var providerGroup: RadioGroup
    private lateinit var progress: ProgressBar
    private lateinit var connectionHintButton: Button
    private lateinit var returnToPanelButton: Button
    private lateinit var backHandler: OnBackPressedCallback

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
        val raw = result.contents ?: return@registerForActivityResult
        val provider = selectedProvider()
        val valid = ConnectionUrl.validate(raw, provider)
        if (valid == null) {
            toast(getString(R.string.qr_invalid))
        } else {
            urlInput.setText(valid)
            urlInput.setSelection(valid.length)
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
            // The native root already keeps content outside system bars/cutouts.
            // Zero those handled insets before they reach WebView to prevent
            // duplicate CSS safe-area padding on recent Android System WebViews.
            val handledTypes = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            val bars = insets.getInsets(handledTypes)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.Builder(insets)
                .setInsets(handledTypes, Insets.NONE)
                .build()
        }
        store = ConnectionStore(this)
        setupPanel = findViewById(R.id.setupPanel)
        webPanel = findViewById(R.id.webPanel)
        errorPanel = findViewById(R.id.errorPanel)
        webContainer = findViewById(R.id.webContainer)
        urlInput = findViewById(R.id.urlInput)
        providerGroup = findViewById(R.id.providerGroup)
        progress = findViewById(R.id.loadingProgress)
        connectionHintButton = findViewById(R.id.changeButton)
        returnToPanelButton = findViewById(R.id.returnToPanelButton)

        backHandler = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    setupPanel.visibility == View.VISIBLE && canReturnToPanel() -> returnToPanel()
                    webPanel.visibility == View.VISIBLE && webView?.canGoBack() == true -> webView?.goBack()
                    webPanel.visibility == View.VISIBLE -> showBackMenu()
                    errorPanel.visibility == View.VISIBLE -> showSetup()
                    else -> finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backHandler)

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            qrScanner.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.qr_prompt))
                setBeepEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(PortraitQrCaptureActivity::class.java)
            })
        }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        findViewById<Button>(R.id.retryButton).setOnClickListener { loadPanel() }
        findViewById<Button>(R.id.editButton).setOnClickListener { showSetup() }
        connectionHintButton.setOnClickListener { showSetup() }
        returnToPanelButton.setOnClickListener { returnToPanel() }

        current = store.read()
        if (current != null) loadPanel() else showSetup()
    }

    private fun selectedProvider(): ConnectionUrl.Provider = when (providerGroup.checkedRadioButtonId) {
        R.id.zrokOption -> ConnectionUrl.Provider.ZROK
        R.id.tailscaleOption -> ConnectionUrl.Provider.TAILSCALE
        else -> ConnectionUrl.Provider.CLOUDFLARE
    }

    private fun canReturnToPanel(): Boolean = panelReady && webView != null && current != null

    private fun showBackMenu() {
        AlertDialog.Builder(this)
            .setTitle(R.string.back_menu_title)
            .setMessage(R.string.back_menu_message)
            .setPositiveButton(R.string.change_connection) { _, _ -> showSetup() }
            .setNeutralButton(R.string.exit_app) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun showSetup() {
        // Editing is not a logout: keep the loaded WebView, cookies and history untouched.
        stopTimeout()
        current?.let { saved ->
            val radioId = when (saved.provider) {
                ConnectionUrl.Provider.CLOUDFLARE -> R.id.cloudflareOption
                ConnectionUrl.Provider.ZROK -> R.id.zrokOption
                ConnectionUrl.Provider.TAILSCALE -> R.id.tailscaleOption
            }
            providerGroup.check(radioId)
            urlInput.setText(saved.url)
        }
        returnToPanelButton.visibility = if (canReturnToPanel()) View.VISIBLE else View.GONE
        showOnly(setupPanel)
    }

    private fun returnToPanel() {
        if (!canReturnToPanel()) return
        // Do not loadUrl(), recreate WebView or save the partially edited form.
        showOnly(webPanel)
    }

    private fun connect() {
        val provider = selectedProvider()
        val validated = ConnectionUrl.validate(urlInput.text.toString(), provider)
        if (validated == null) {
            urlInput.error = getString(R.string.invalid_url)
            return
        }
        val old = current
        if (old?.provider == provider && old?.url == validated && canReturnToPanel()) {
            // Even pressing "Conectar" with an unchanged address must preserve login/position.
            returnToPanel()
            return
        }
        if (old?.provider != provider || old?.url != validated) {
            // An explicit change of connection begins a fresh browsing history.
            resetWebView()
        }
        current = ConnectionStore.Saved(provider, validated)
        store.save(current!!)
        loadPanel()
    }

    private fun showOnly(panel: View) {
        hideConnectionHint()
        setupPanel.visibility = if (panel === setupPanel) View.VISIBLE else View.GONE
        webPanel.visibility = if (panel === webPanel) View.VISIBLE else View.GONE
        errorPanel.visibility = if (panel === errorPanel) View.VISIBLE else View.GONE
        backHandler.isEnabled = panel !== setupPanel || canReturnToPanel()
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
            // One-time, short-lived shortcut. Back at the web root works thereafter.
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
