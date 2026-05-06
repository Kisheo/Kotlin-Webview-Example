package com.inokisheo.kotlinwebview

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.SslErrorHandler
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
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.inokisheo.kotlinwebview.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineView: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    companion object {
        private const val TAG = "MainActivity"
        private const val HOME_URL = "https://www.google.com/"

        /** Known Google ad-network hostnames to block. */
        private val AD_HOSTS = setOf(
            "pagead2.googlesyndication.com",
            "googlesyndication.com",
            "googleads.g.doubleclick.net",
            "adservices.google.com",
            "partner.googleadservices.com",
            "tpc.googlesyndication.com",
            "adservice.google.com",
            "adservice.google.co",
            "doubleclick.net",
            "ads.google.com",
            "adssettings.google.com",
            "imasdk.googleapis.com"
        )

        /** URL path substrings that indicate ad scripts. */
        private val AD_URL_PATTERNS = listOf(
            "/adsbygoogle.js",
            "/pagead/js/adsbygoogle.js",
            "/ads/ads.js"
        )
    }

    // -----------------------------------------------------------------------
    // Activity-result launchers (replaces deprecated onActivityResult)
    // -----------------------------------------------------------------------

    /** Handles the file-chooser intent result for WebView file uploads. */
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                    ?: result.data?.data?.let { arrayOf(it) }
            } else null
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    /** Requests runtime permissions (camera, mic, storage, location). */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { !it }) {
                Toast.makeText(
                    this,
                    getString(R.string.permissions_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView
        progressBar = binding.progressBar
        offlineView = binding.offlineView.root

        setupWebView()
        setupOfflineRetry()
        setupBackPress()

        if (isOnline()) {
            webView.loadUrl(HOME_URL)
        } else {
            showOffline()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // WebView setup
    // -----------------------------------------------------------------------

    private fun setupWebView() {
        val settings = webView.settings
        // JavaScript & storage
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // File / content access
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        // Display
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // Zoom
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // Cache
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // Media
        settings.mediaPlaybackRequiresUserGesture = false

        // Mixed content + third-party cookies (API 21+)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // AppCache is deprecated in API 33+ — suppress for older targets
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            @Suppress("DEPRECATION")
            settings.setAppCacheEnabled(true)
        }

        // Enable remote debugging in debug builds
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.webChromeClient = buildChromeClient()
        webView.webViewClient = buildWebViewClient()
        webView.setDownloadListener(::handleDownload)
    }

    // -----------------------------------------------------------------------
    // WebChromeClient — progress, title, file chooser, geolocation
    // -----------------------------------------------------------------------

    private fun buildChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            Log.d(TAG, "Page title: $title")
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            // Cancel any pending callback before setting a new one
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback

            val intent = fileChooserParams?.createIntent()
                ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }

            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Cannot open file chooser", e)
                this@MainActivity.filePathCallback = null
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.cannot_open_file_chooser),
                    Toast.LENGTH_SHORT
                ).show()
                false
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                callback?.invoke(origin, true, false)
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                callback?.invoke(origin, false, false)
            }
        }
    }

    // -----------------------------------------------------------------------
    // WebViewClient — ad-blocking, SSL, error handling
    // -----------------------------------------------------------------------

    private fun buildWebViewClient(): WebViewClient = object : WebViewClient() {

        /**
         * Block requests to known Google ad-network hosts and ad-script URLs.
         * Returns an empty [WebResourceResponse] for any matched URL, causing
         * the resource to appear as an empty successful response so page layout
         * is not broken by network errors.
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            return if (isAdUrl(url)) {
                Log.d(TAG, "Blocked ad resource: $url")
                WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    HttpURLConnection.HTTP_NO_CONTENT,
                    "No Content",
                    emptyMap(),
                    ByteArrayInputStream(ByteArray(0))
                )
            } else {
                super.shouldInterceptRequest(view, request)
            }
        }

        /**
         * Prompt the user on SSL errors rather than silently proceeding.
         * Default is to cancel; the user must explicitly tap "Continue".
         */
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler,
            error: SslError
        ) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.ssl_error_title)
                .setMessage(R.string.ssl_error_message)
                .setPositiveButton(R.string.ssl_continue) { _, _ -> handler.proceed() }
                .setNegativeButton(R.string.ssl_cancel) { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .show()
        }

        /** Show offline UI when the main frame fails to load. */
        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                Log.e(TAG, "WebView main-frame error: ${error?.description}")
                showOffline()
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            CookieManager.getInstance().flush()
            if (isOnline()) hideOffline()
        }
    }

    // -----------------------------------------------------------------------
    // Download handling
    // -----------------------------------------------------------------------

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        @Suppress("UNUSED_PARAMETER") contentLength: Long
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                addRequestHeader("User-Agent", userAgent)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                )
                setMimeType(mimeType)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $url", e)
            Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // -----------------------------------------------------------------------
    // Ad-blocking helpers
    // -----------------------------------------------------------------------

    /**
     * Returns `true` if [url] matches a known Google ad-network hostname or
     * a recognised ad-script URL path pattern.
     */
    private fun isAdUrl(url: String): Boolean {
        return AD_HOSTS.any { host -> url.contains(host, ignoreCase = true) } ||
                AD_URL_PATTERNS.any { pattern -> url.contains(pattern, ignoreCase = true) }
    }

    // -----------------------------------------------------------------------
    // Offline / connectivity
    // -----------------------------------------------------------------------

    private fun setupOfflineRetry() {
        offlineView.findViewById<Button>(R.id.btnRetry).setOnClickListener {
            if (isOnline()) {
                val target = webView.url?.takeIf { it.isNotBlank() } ?: HOME_URL
                hideOffline()
                webView.loadUrl(target)
            } else {
                Toast.makeText(this, getString(R.string.still_offline), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOffline() {
        offlineView.visibility = View.VISIBLE
        webView.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun hideOffline() {
        offlineView.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    /** Returns `true` when the device has an active network with internet access. */
    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val nc = cm.getNetworkCapabilities(network) ?: return false
        return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // -----------------------------------------------------------------------
    // Back-press — navigate WebView history or show exit dialog
    // -----------------------------------------------------------------------

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    showExitDialog()
                }
            }
        })
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.exit_no, null)
            .show()
    }
}
