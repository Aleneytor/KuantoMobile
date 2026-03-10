package com.kuanto.webview

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.play.core.review.ReviewManagerFactory
import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings.*
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var adView: AdView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineLayout: View
    private lateinit var btnRetry: Button

    // Estado de scroll sincronizado con JS (volatile para thread-safety)
    @Volatile
    private var webContentAtTop = true

    // File chooser para <input type="file">
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    // ── Permiso de Notificaciones (Android 13+) ──────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("KuantoApp", "Permiso de notificaciones otorgado")
        } else {
            Log.d("KuantoApp", "Permiso de notificaciones denegado")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // ── Splash Screen (Debe ir antes de super.onCreate) ──────────
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ── Vincular Vistas ──────────────────────────────────────────
        webView = findViewById(R.id.webView)
        adView = findViewById(R.id.adView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        offlineLayout = findViewById(R.id.offlineLayout)
        btnRetry = findViewById(R.id.btnRetry)

        // ── Notificaciones ──────────────────────────────────────────
        NotificationHelper.createNotificationChannel(this)
        checkNotificationPermission()
        scheduleRateUpdates()

        // ── Pantalla completa inmersiva ──────────────────────────────
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // ── Inicializar AdMob SDK ───────────────────────────────────
        MobileAds.initialize(this) { initializationStatus ->
            Log.d("KuantoApp", "AdMob SDK inicializado: $initializationStatus")
        }

        // ── Banner AdMob (fijo abajo) ───────────────────────────────
        adView.loadAd(AdRequest.Builder().build())

        // ── Pull-to-Refresh ─────────────────────────────────────────
        swipeRefresh.setColorSchemeColors(0xFF00CC99.toInt()) // Verde Kuanto
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

        // ── Botón Reintentar (Offline) ──────────────────────────────
        btnRetry.setOnClickListener {
            offlineLayout.visibility = View.GONE
            webView.reload()
        }

        // ── WebView ─────────────────────────────────────────────────
        // Callback SINCRÓNICO: se llama en el momento exacto del touch.
        // Retorna true si el contenido puede subir más (bloquea refresh).
        // Retorna false si está en el top (permite refresh).
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            !webContentAtTop
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "$userAgentString KuantoApp/2.0"
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)

            // Caché inteligente: si hay red, comportamiento normal; si no, usar caché
            cacheMode = if (isNetworkAvailable()) WebSettings.LOAD_DEFAULT
                        else WebSettings.LOAD_CACHE_ELSE_NETWORK
            
            // Forzar Modo Oscuro si el sistema lo requiere
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_AUTO)
            }
        }

        // Puente Android <-> Web para sincronizar el interruptor de notificaciones
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val host = Uri.parse(url).host ?: ""
                // Si es kuanto.online, cargar dentro del WebView
                return if (host.contains("kuanto.online")) {
                    false
                } else {
                    // Abrir enlaces externos en el navegador del sistema
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                offlineLayout.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE

                // Deshabilitar pull-to-refresh y ocultar anuncio en la página de bienvenida
                val currentUrl = url ?: ""
                val isWelcomePage = currentUrl.contains("/welcome")
                
                swipeRefresh.isEnabled = !isWelcomePage
                adView.visibility = if (isWelcomePage) View.GONE else View.VISIBLE
                
                // ── Restaurar localStorage desde SharedPreferences ──
                // Primero obtenemos todas las claves guardadas y las restauramos
                val prefs = getSharedPreferences("kuanto_localstorage", Context.MODE_PRIVATE)
                val allEntries = prefs.all
                val restoreJs = StringBuilder()
                restoreJs.append("(function(){")
                for ((key, value) in allEntries) {
                    val escapedKey = key.replace("\\", "\\\\").replace("'", "\\'")
                    val escapedValue = (value as? String ?: "").replace("\\", "\\\\").replace("'", "\\'")
                    restoreJs.append("try{localStorage.setItem('$escapedKey','$escapedValue');}catch(e){}")
                }
                restoreJs.append("console.log('KuantoBridge: Restored "+allEntries.size+" localStorage entries from native storage');")
                restoreJs.append("})();")
                view?.evaluateJavascript(restoreJs.toString(), null)

                val js = """
                    (function() {
                        if (!window.Notification) {
                            window.Notification = {
                                permission: 'granted',
                                requestPermission: function() { return Promise.resolve('granted'); }
                            };
                        } else {
                            Object.defineProperty(window.Notification, 'permission', { get: function() { return 'granted'; } });
                            window.Notification.requestPermission = function() { return Promise.resolve('granted'); };
                        }

                        if (navigator.permissions && navigator.permissions.query) {
                            const originalQuery = navigator.permissions.query;
                            navigator.permissions.query = function(q) {
                                if (q.name === 'notifications') {
                                    return Promise.resolve({ state: 'granted', onchange: null });
                                }
                                return originalQuery.call(navigator, q);
                            };
                        }

                        // ── Interceptar localStorage para persistir en Android ──
                        var originalSetItem = localStorage.setItem;
                        localStorage.setItem = function(key, value) {
                            // Guardar en almacenamiento nativo de Android
                            if (window.AndroidBridge) {
                                try { window.AndroidBridge.saveToStorage(key, String(value)); } catch(e) {}
                            }
                            if (key === '@app_notifications') {
                                if (window.AndroidBridge) {
                                    window.AndroidBridge.toggleNotifications(value === 'true' || value === true);
                                }
                            }
                            return originalSetItem.apply(this, arguments);
                        };
                        var originalRemoveItem = localStorage.removeItem;
                        localStorage.removeItem = function(key) {
                            if (window.AndroidBridge) {
                                try { window.AndroidBridge.removeFromStorage(key); } catch(e) {}
                            }
                            return originalRemoveItem.apply(this, arguments);
                        };
                        var originalClear = localStorage.clear;
                        localStorage.clear = function() {
                            if (window.AndroidBridge) {
                                try { window.AndroidBridge.clearStorage(); } catch(e) {}
                            }
                            return originalClear.apply(this, arguments);
                        };
                        // ── Scroll Monitor para Pull-to-Refresh ──
                        // La PWA usa un contenedor interno para scroll,
                        // así que window.pageYOffset siempre es 0.
                        // Detectamos dinámicamente qué elemento hace scroll.
                        var _scrollContainer = null;
                        
                        // Capturar qué elemento realmente tiene scroll
                        document.addEventListener('scroll', function(e) {
                            var target = e.target;
                            if (target && target !== document && target.scrollHeight > target.clientHeight) {
                                _scrollContainer = target;
                            }
                        }, true);
                        
                        function getScrollTop() {
                            // 1. Revisar el contenedor detectado
                            if (_scrollContainer) {
                                return _scrollContainer.scrollTop;
                            }
                            // 2. Revisar window scroll
                            var ws = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
                            if (ws > 0) return ws;
                            // 3. Buscar cualquier elemento con scroll activo
                            var candidates = document.querySelectorAll('main, [role="main"], #root > div, #__next > div, .app-content, .page-content');
                            for (var i = 0; i < candidates.length; i++) {
                                if (candidates[i].scrollTop > 0) {
                                    _scrollContainer = candidates[i];
                                    return candidates[i].scrollTop;
                                }
                            }
                            return 0;
                        }
                        
                        function reportScroll() {
                            var st = getScrollTop();
                            var isTop = st < 5;
                            if (window.AndroidBridge) {
                                window.AndroidBridge.updateScrollTop(isTop);
                            }
                        }
                        
                        // Escuchar scroll en la fase de captura
                        window.addEventListener('scroll', reportScroll, true);
                        document.addEventListener('scroll', reportScroll, true);
                        // touchstart para actualizar ANTES del gesto
                        window.addEventListener('touchstart', reportScroll, true);
                        document.addEventListener('touchstart', reportScroll, true);
                        // touchmove para seguir actualizando durante el gesto  
                        window.addEventListener('touchmove', reportScroll, true);
                        // Respaldo periódico
                        setInterval(reportScroll, 300);
                        // Revisar inmediatamente
                        reportScroll();
                        
                        console.log('KuantoBridge: Scroll Monitor v3 activado (detección dinámica de contenedor)');

                        // ── Configurar botones Compartir y Calificar ──
                        var _shareWired = false;
                        var _rateWired = false;
                        
                        function wireButtons() {
                            if (_shareWired && _rateWired) return;
                            
                            var allElements = document.querySelectorAll('div, span, button, a, li, p');
                            for (var i = 0; i < allElements.length; i++) {
                                var el = allElements[i];
                                var text = (el.innerText || '').trim();
                                
                                // Solo matchear elementos con texto corto 
                                // (el botón + subtítulo, no contenedores padre)
                                if (text.length > 60 || text.length < 5) continue;
                                
                                if (!_shareWired && text.indexOf('Compartir App') >= 0 && text.indexOf('Califica') < 0) {
                                    _shareWired = true;
                                    (function(target) {
                                        target.addEventListener('click', function(e) {
                                            e.preventDefault();
                                            e.stopPropagation();
                                            if (window.AndroidBridge) window.AndroidBridge.shareApp();
                                        }, true);
                                    })(el);
                                    console.log('KuantoBridge: Compartir wired to', el.tagName, text.substring(0, 30));
                                }
                                
                                if (!_rateWired && text.indexOf('Califica la App') >= 0 && text.indexOf('Compartir') < 0) {
                                    _rateWired = true;
                                    (function(target) {
                                        target.addEventListener('click', function(e) {
                                            e.preventDefault();
                                            e.stopPropagation();
                                            if (window.AndroidBridge) window.AndroidBridge.rateApp();
                                        }, true);
                                    })(el);
                                    console.log('KuantoBridge: Calificar wired to', el.tagName, text.substring(0, 30));
                                }
                            }
                        }
                        
                        setInterval(wireButtons, 1500);
                        var observer = new MutationObserver(function() {
                            setTimeout(wireButtons, 500);
                        });
                        observer.observe(document.body, { childList: true, subtree: true });
                        wireButtons();
                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT) {
                    // Cambiar a modo caché offline
                    webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    offlineLayout.visibility = View.VISIBLE
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Error de Seguridad")
                    .setMessage("El certificado SSL del sitio no es válido. ¿Deseas continuar?")
                    .setPositiveButton("Continuar") { _, _ -> handler?.proceed() }
                    .setNegativeButton("Cancelar") { _, _ -> handler?.cancel() }
                    .setCancelable(false)
                    .show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                }
            }

            // Soporte para <input type="file">
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                fileChooserLauncher.launch("*/*")
                return true
            }

            // Manejar target="_blank" — abrir en el mismo WebView
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                val result = view?.hitTestResult
                val url = result?.extra
                if (url != null) {
                    view.loadUrl(url)
                }
                return false
            }
        }

        // ── Descargas ────────────────────────────────────────────────
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val request = android.app.DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimetype)
            request.addRequestHeader("User-Agent", userAgent)
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
            request.setDescription("Descargando archivo...")
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimetype)
            )
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Descargando...", Toast.LENGTH_SHORT).show()
        }

        handleIntent(intent)

        // ── Botón Atrás ─────────────────────────────────────────────
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // ── In-App Review (después de 3 aperturas) ────────────────
        checkAndPromptReview()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val appLinkData: Uri? = intent?.data
        if (appLinkData != null) {
            Log.d("KuantoApp", "Abriendo desde Deep Link: $appLinkData")
            webView.loadUrl(appLinkData.toString())
        } else if (webView.url == null) {
            webView.loadUrl("https://kuanto.online/")
        }
    }

    // Clase para recibir comandos desde la Web (JS)
    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun updateScrollTop(isTop: Boolean) {
            webContentAtTop = isTop
        }

        @android.webkit.JavascriptInterface
        fun toggleNotifications(enabled: Boolean) {
            if (enabled) {
                Log.d("KuantoApp", "Web activó notificaciones -> Programando WorkManager")
                scheduleRateUpdates()
            } else {
                Log.d("KuantoApp", "Web desactivó notificaciones -> Cancelando WorkManager")
                WorkManager.getInstance(this@MainActivity).cancelUniqueWork("KuantoRateUpdates")
            }
        }

        @android.webkit.JavascriptInterface
        fun shareApp() {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Kuanto - Tasas en Tiempo Real")
                putExtra(Intent.EXTRA_TEXT, "¡Descarga Kuanto y mantente al día con las tasas del BCV, Euro y USDT!\nhttps://play.google.com/store/apps/details?id=com.aleneytor.app")
            }
            startActivity(Intent.createChooser(shareIntent, "Compartir vía"))
        }

        @android.webkit.JavascriptInterface
        fun rateApp() {
            runOnUiThread {
                try {
                    // Intentar In-App Review (funciona cuando se instala desde Play Store)
                    val manager = ReviewManagerFactory.create(this@MainActivity)
                    manager.requestReviewFlow().addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            manager.launchReviewFlow(this@MainActivity, task.result)
                                .addOnCompleteListener {
                                    // Google no informa si se mostró realmente,
                                    // así que no hacemos nada extra aquí
                                }
                        } else {
                            openPlayStore()
                        }
                    }
                } catch (e: Exception) {
                    openPlayStore()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun saveToStorage(key: String, value: String) {
            val prefs = getSharedPreferences("kuanto_localstorage", Context.MODE_PRIVATE)
            prefs.edit().putString(key, value).apply()
        }

        @android.webkit.JavascriptInterface
        fun getFromStorage(key: String): String? {
            val prefs = getSharedPreferences("kuanto_localstorage", Context.MODE_PRIVATE)
            return prefs.getString(key, null)
        }

        @android.webkit.JavascriptInterface
        fun removeFromStorage(key: String) {
            val prefs = getSharedPreferences("kuanto_localstorage", Context.MODE_PRIVATE)
            prefs.edit().remove(key).apply()
        }

        @android.webkit.JavascriptInterface
        fun clearStorage() {
            val prefs = getSharedPreferences("kuanto_localstorage", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }
    }

    private fun openPlayStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=com.aleneytor.app")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=com.aleneytor.app")))
        }
    }

    private fun checkNotificationPermission() {
        val version = Build.VERSION.SDK_INT
        Log.d("KuantoApp", "CheckPermission: Android API $version")
        
        if (version >= Build.VERSION_CODES.TIRAMISU) {
            val status = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (status != PackageManager.PERMISSION_GRANTED) {
                Log.d("KuantoApp", "Solicitando permiso...")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d("KuantoApp", "Permiso ya otorgado")
            }
        } else {
            Log.d("KuantoApp", "Versión inferior a 13, permiso concedido por defecto")
        }
    }

    private fun scheduleRateUpdates() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val rateWorkRequest = PeriodicWorkRequestBuilder<RateUpdateWorker>(
            30, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "KuantoRateUpdates",
            ExistingPeriodicWorkPolicy.KEEP,
            rateWorkRequest
        )
    }

    // ── Conectividad ───────────────────────────────────────────
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── In-App Review ─────────────────────────────────────────
    private fun checkAndPromptReview() {
        val prefs = getSharedPreferences("kuanto_prefs", Context.MODE_PRIVATE)
        val openCount = prefs.getInt("open_count", 0) + 1
        prefs.edit().putInt("open_count", openCount).apply()

        // Mostrar solo en la 3ra apertura y no más de una vez
        if (openCount == 3 && !prefs.getBoolean("review_shown", false)) {
            prefs.edit().putBoolean("review_shown", true).apply()
            val manager = ReviewManagerFactory.create(this)
            manager.requestReviewFlow().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    manager.launchReviewFlow(this, task.result)
                }
            }
        }
    }

    override fun onPause() {
        adView.pause()
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        adView.resume()
        // Restaurar caché si hay conexión
        if (isNetworkAvailable()) {
            webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    override fun onDestroy() {
        adView.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
