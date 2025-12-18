package com.inno.kiosk.ui.kiosk

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.inno.kiosk.R
import com.inno.kiosk.data.SecurePrefs
import com.inno.kiosk.kiosk.KioskPolicy
import com.inno.kiosk.ui.setup.SetupActivity
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class KioskActivity : AppCompatActivity() {

    private lateinit var web: WebView

    // --- ORB guard ---
    private var seenOrbOnMedia = false
    private var orbLogOnce = false

    // --- Top overlay blocker (HARD status bar swipe guard) ---
    private var topBlocker: View? = null

    private val TOP_BLOCKER_DP = 10          // зона, где “съедаем” свайпы сверху
    private val HOTZONE_DP = 10              // дырка в верхнем правом углу под админ-лонгпресс

    // --- Admin long-press ---
    private var downX = 0f
    private var downY = 0f
    private var isFingerDown = false
    private var adminTriggered = false
    private val ADMIN_HOLD_MS = 5000L

    private val handler = Handler(Looper.getMainLooper())

    private val adminHoldRunnable = Runnable {
        if (!adminTriggered && isFingerDown) {
            adminTriggered = true
            val prefs = SecurePrefs(this)
            val inHotzone = isInTopRightHotzone(downX, downY)
            showAdminDialog(prefs, inHotzone)
        }
    }

    // --- Watchdog ---
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogSeq = AtomicInteger(0)

    private val WATCHDOG_PING_EVERY_MS = 30_000L
    private val WATCHDOG_TIMEOUT_MS = 8_000L

    private var waitingPong = false

    private val watchdogPingRunnable = object : Runnable {
        override fun run() {
            pingWebView()
            watchdogHandler.postDelayed(this, WATCHDOG_PING_EVERY_MS)
        }
    }

    private val watchdogTimeoutRunnable = Runnable {
        if (waitingPong) {
            waitingPong = false

            // ⚠️ если ORB блокирует медиа — рестарт будет только усугублять цикл
            if (seenOrbOnMedia) {
                android.util.Log.w("KIOSK_WEB", "Watchdog timeout but ORB on media seen → skip restart")
                return@Runnable
            }

            Toast.makeText(this, "WebView подвис — перезапускаю", Toast.LENGTH_SHORT).show()
            softRestartWebView(keepUrl = true)
        }
    }

    // ✅ анти-спам рестартов при unresponsive renderer
    private var lastRendererRestartAt = 0L
    private val RENDERER_RESTART_COOLDOWN_MS = 20_000L

    // ✅ splash = первая реально показанная страница (не полагаемся на URL)
    private var firstPage = true
    private var lastUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_kiosk)
        hideSystemUi()

        // ✅ ВАЖНО: policy ДО startLockTask
        KioskPolicy.apply(this)
        try { startLockTask() } catch (_: Throwable) {}

        // ✅ Жёсткий блок “шторки” через overlay
        installTopSwipeBlocker(forceRecreate = true)

        // ✅ Дополнительно: исключаем верхнюю область из системных жестов (Android 10+)
        applySystemGestureExclusion()

        val prefs = SecurePrefs(this)

        web = findViewById(R.id.webView)
        configureWebView(web)
        web.settings.textZoom = 100

        web.loadUrl(prefs.getBaseUrl() ?: DEFAULT_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack()
            }
        })

        watchdogHandler.removeCallbacks(watchdogPingRunnable)
        watchdogHandler.postDelayed(watchdogPingRunnable, 5_000L)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        try { startLockTask() } catch (_: Throwable) {}

        installTopSwipeBlocker(forceRecreate = false)
        applySystemGestureExclusion()

        try {
            if (this::web.isInitialized) {
                web.resumeTimers()
                web.onResume()
            }
        } catch (_: Throwable) {}
    }

    override fun onPause() {
        try {
            if (this::web.isInitialized) {
                web.onPause()
                web.pauseTimers()
            }
        } catch (_: Throwable) {}
        super.onPause()
    }

    /**
     * ✅ Long-press для админа.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                adminTriggered = false
                isFingerDown = true
                downX = ev.rawX
                downY = ev.rawY

                handler.removeCallbacks(adminHoldRunnable)
                handler.postDelayed(adminHoldRunnable, ADMIN_HOLD_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                val slop = 24 * resources.displayMetrics.density
                if (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop) {
                    isFingerDown = false
                    handler.removeCallbacks(adminHoldRunnable)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isFingerDown = false
                handler.removeCallbacks(adminHoldRunnable)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------
    // HARD status-bar swipe blocker
    // ---------------------------
    private fun installTopSwipeBlocker(forceRecreate: Boolean) {
        val root = window.decorView as? ViewGroup ?: return

        if (forceRecreate) {
            removeTopSwipeBlocker()
        } else {
            if (topBlocker != null && topBlocker?.parent != null) return
        }

        val d = resources.displayMetrics.density
        val blockerH = (TOP_BLOCKER_DP * d).toInt()
        val hot = (HOTZONE_DP * d)
        val w = resources.displayMetrics.widthPixels.toFloat()

        val v = View(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                blockerH
            )

            isClickable = true
            isFocusable = true
            elevation = 10000f

            setOnTouchListener { _, ev ->
                val x = ev.rawX
                val y = ev.rawY
                val inTopRight = (x >= (w - hot)) && (y <= hot)
                if (inTopRight) false else true
            }
        }

        root.addView(v)
        topBlocker = v
        android.util.Log.w("KIOSK", "TopSwipeBlocker installed h=${blockerH}px")
    }

    private fun removeTopSwipeBlocker() {
        try {
            val parent = topBlocker?.parent as? ViewGroup
            parent?.removeView(topBlocker)
        } catch (_: Throwable) {
        } finally {
            topBlocker = null
        }
    }

    /**
     * Android 10+ умеет исключать области из system gestures.
     */
    private fun applySystemGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val d = resources.displayMetrics.density
        val h = (TOP_BLOCKER_DP * d).toInt()
        val rect = Rect(0, 0, resources.displayMetrics.widthPixels, h)

        window.decorView.post {
            try {
                window.decorView.systemGestureExclusionRects = listOf(rect)
            } catch (_: Throwable) {}
        }
    }

    // ---------------------------
    // Admin helpers
    // ---------------------------
    private fun isInTopRightHotzone(xRaw: Float, yRaw: Float): Boolean {
        val density = resources.displayMetrics.density
        val hot = (HOTZONE_DP * density)
        val w = resources.displayMetrics.widthPixels.toFloat()
        return (xRaw >= w - hot) && (yRaw <= hot)
    }

    private fun showAdminDialog(prefs: SecurePrefs, hotzone: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Admin password"
        }

        val title = if (hotzone) "Администратор (угол)" else "Администратор"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Введите пароль")
            .setView(input)
            .setCancelable(true)
            .setPositiveButton("Войти") { _, _ ->
                val entered = input.text?.toString().orEmpty()
                val real = prefs.getAdminPassword().orEmpty()

                if (real.isNotBlank() && entered == real) {
                    showAdminMenu(prefs)
                } else {
                    Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showAdminMenu(prefs: SecurePrefs) {
        val items = arrayOf(
            "🔄 Обновить страницу",
            "♻️ Перезапустить WebView (без очистки токена)",
            "🧹 Очистить Cache + Cookies + Storage (вкл. IndexedDB)",
            "🧯 Освободить память видео (release)",
            "🚪 Выйти из киоска (stopLockTask)",
            "♻️ Сбросить настройки (вернуться в Setup)"
        )

        AlertDialog.Builder(this)
            .setTitle("Админ меню")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        web.reload()
                        Toast.makeText(this, "Обновлено", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        softRestartWebView(keepUrl = true)
                        Toast.makeText(this, "WebView перезапущен", Toast.LENGTH_SHORT).show()
                    }
                    2 -> clearWebViewDataFull()
                    3 -> {
                        releaseVideoMemory()
                        Toast.makeText(this, "Попробовал освободить память видео", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        try { stopLockTask() } catch (_: Throwable) {}
                        Toast.makeText(this, "LockTask остановлен", Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        prefs.reset()
                        try { stopLockTask() } catch (_: Throwable) {}
                        startActivity(
                            Intent(this, SetupActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ---------------------------
    // WebView stuff
    // ---------------------------
    private fun clearWebViewDataFull() {
        try {
            web.stopLoading()
            web.loadUrl("about:blank")

            web.clearCache(true)
            web.clearHistory()
            web.clearFormData()

            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()

            WebStorage.getInstance().deleteAllData()

            web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            web.loadUrl(getKioskUrl())

            Toast.makeText(this, "Очищено (вкл. IndexedDB)", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Ошибка очистки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun configureWebView(w: WebView) {
        WebView.setWebContentsDebuggingEnabled(true)

        // ✅ держим рендерер WebView “важным” — без Unresolved reference (через reflection)
        trySetRendererPriorityImportant()

        // ✅ Контроль рендер-процесса WebView (Android 10+): если завис — soft restart (с cooldown)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                w.setWebViewRenderProcessClient(object : WebViewRenderProcessClient() {

                    override fun onRenderProcessUnresponsive(
                        view: WebView,
                        renderer: WebViewRenderProcess?
                    ) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRendererRestartAt < RENDERER_RESTART_COOLDOWN_MS) return
                        lastRendererRestartAt = now

                        android.util.Log.w("KIOSK_WEB", "WebView renderer UNRESPONSIVE → soft restart")
                        if (!seenOrbOnMedia) {
                            handler.post { softRestartWebView(keepUrl = true) }
                        }
                    }

                    override fun onRenderProcessResponsive(
                        view: WebView,
                        renderer: WebViewRenderProcess?
                    ) {
                        android.util.Log.d("KIOSK_WEB", "WebView renderer responsive")
                    }
                })
            } catch (_: Throwable) {}
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("KIOSK_WEB", "console: ${m.message()} @${m.lineNumber()} ${m.sourceId()}")
                return true
            }
        }

        w.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                seenOrbOnMedia = false
                orbLogOnce = false

                // ✅ освобождаем видео-буферы ТОЛЬКО при уходе с первой (splash) страницы на другую
                val prev = lastUrl
                if (firstPage && prev != null && url != null && url != prev) {
                    handler.postDelayed({ releaseVideoMemory() }, 800L)
                    firstPage = false
                }

                lastUrl = url
                android.util.Log.d("KIOSK_WEB", "onPageStarted: $url")
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (!seenOrbOnMedia) {
                    injectVideoNoPlayOverlay(view)
                    forceAutoplayVideos(view)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                android.util.Log.d("KIOSK_WEB", "onPageFinished: $url")
                if (!seenOrbOnMedia) {
                    injectVideoNoPlayOverlay(view)
                    forceAutoplayVideos(view)
                }
                // ❌ ВАЖНО: НЕ чистим видео по таймеру здесь — иначе длинные видео покажут "play"
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (isMediaRequest(request) && isOrbBlocked(error)) {
                    seenOrbOnMedia = true
                    if (!orbLogOnce) {
                        orbLogOnce = true
                        android.util.Log.e("KIOSK_WEB", "ERR_BLOCKED_BY_ORB on media: ${request?.url}")
                    }
                    return
                }
                android.util.Log.e("KIOSK_WEB", "onReceivedError: ${request?.url} ${error?.description}")
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                android.util.Log.e("KIOSK_WEB", "onReceivedHttpError: ${request?.url} status=${errorResponse?.statusCode}")
            }
        }

        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT

        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false

        s.loadsImagesAutomatically = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        // ✅ аппаратное ускорение оставляем (нужно для видео)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // ✅ безопасность/совместимость
        s.allowFileAccess = false
        s.allowContentAccess = true

        try {
            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            }
        } catch (_: Throwable) {}
    }

    /**
     * WebView.setRendererPriorityPolicy может отсутствовать в stubs при compileSdk,
     * поэтому вызываем через reflection (без Unresolved reference).
     */
    private fun trySetRendererPriorityImportant() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val clazz = WebView::class.java

            val important = try {
                clazz.getField("RENDERER_PRIORITY_IMPORTANT").getInt(null)
            } catch (_: Throwable) {
                1
            }

            val m = clazz.getMethod(
                "setRendererPriorityPolicy",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            m.invoke(null, important, true)
        } catch (_: Throwable) {
        }
    }

    /**
     * ✅ Убираем "Play" пока видео не начнёт реально играть:
     * - видео невидимо (opacity 0) до playing/timeupdate
     * - скрываем нативные webkit-контролы
     * - скрываем типовые overlay-кнопки популярных плееров (videojs/plyr/youtube)
     */
    private fun injectVideoNoPlayOverlay(view: WebView?) {
        view ?: return
        view.evaluateJavascript(
            """
            (function(){
              try {
                if (!document.getElementById('kioskVideoFixStyle')) {
                  var st = document.createElement('style');
                  st.id = 'kioskVideoFixStyle';
                  st.textContent = `
                    video { background: transparent !important; }
                    video.kiosk-video-fix { opacity: 0 !important; transition: opacity .12s linear; }
                    video.kiosk-video-fix.kiosk-playing { opacity: 1 !important; }

                    video[controls] { display:none !important; }
                    video::-webkit-media-controls { display:none !important; }
                    video::-webkit-media-controls-enclosure { display:none !important; }
                    video::-webkit-media-controls-panel { display:none !important; }
                    video::-webkit-media-controls-overlay-play-button { display:none !important; }
                    video::-webkit-media-controls-play-button { display:none !important; }

                    .vjs-big-play-button, .plyr__control--overlaid, .ytp-large-play-button {
                      display:none !important; opacity:0 !important; visibility:hidden !important;
                    }
                  `;
                  document.head.appendChild(st);
                }

                function markPlaying(v){
                  try { v.classList.add('kiosk-playing'); } catch(e){}
                }

                document.querySelectorAll('video').forEach(function(v){
                  v.classList.add('kiosk-video-fix');

                  v.muted = true;
                  v.playsInline = true;
                  v.setAttribute('playsinline','');
                  v.setAttribute('webkit-playsinline','');
                  v.setAttribute('muted','');
                  v.setAttribute('autoplay','');
                  v.setAttribute('preload','auto');

                  v.removeAttribute('controls');
                  v.controls = false;

                  if (!v.paused && v.readyState >= 2) markPlaying(v);

                  v.addEventListener('playing', function(){ markPlaying(v); }, { once: true });

                  v.addEventListener('timeupdate', function(){
                    if (v.currentTime > 0 && !v.paused) markPlaying(v);
                  }, { once: true });
                });
              } catch(e) {}
              return "ok";
            })();
            """.trimIndent(),
            null
        )
    }

    private fun forceAutoplayVideos(view: WebView?) {
        view ?: return
        view.evaluateJavascript(
            """
            (function(){
              try {
                document.querySelectorAll('video').forEach(function(v){
                  v.muted = true;
                  v.playsInline = true;
                  v.setAttribute('playsinline','');
                  v.setAttribute('webkit-playsinline','');
                  v.setAttribute('muted','');
                  v.setAttribute('autoplay','');
                  v.preload = 'auto';
                  v.removeAttribute('controls');
                  v.controls = false;
                  if (v.paused) { v.play().catch(function(){}); }
                });
              } catch (e) {}
              return "ok";
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * ✅ Освобождаем видео-буферы: pause + remove src + load()
     * Вызываем ТОЛЬКО после ухода со splash/первой страницы или вручную из админки.
     */
    private fun releaseVideoMemory() {
        if (!this::web.isInitialized) return
        try {
            web.evaluateJavascript(
                """
                (function(){
                  try {
                    document.querySelectorAll('video').forEach(function(v){
                      try { v.pause(); } catch(e){}
                      try { v.removeAttribute('src'); } catch(e){}
                      try {
                        var ss = v.querySelectorAll('source');
                        ss.forEach(function(s){ s.removeAttribute('src'); });
                      } catch(e){}
                      try { v.load(); } catch(e){}
                    });
                  } catch(e){}
                  return "ok";
                })();
                """.trimIndent(),
                null
            )
        } catch (_: Throwable) {}
    }

    private fun getKioskUrl(): String =
        SecurePrefs(this).getBaseUrl() ?: DEFAULT_URL

    private fun pingWebView() {
        if (!this::web.isInitialized) return
        if (waitingPong) return

        val seq = watchdogSeq.incrementAndGet()
        waitingPong = true

        watchdogHandler.removeCallbacks(watchdogTimeoutRunnable)
        watchdogHandler.postDelayed(watchdogTimeoutRunnable, WATCHDOG_TIMEOUT_MS)

        try {
            web.evaluateJavascript("(function(){return 'pong:$seq'})();") { value ->
                if (!waitingPong) return@evaluateJavascript
                val ok = value != null && value.contains("pong:$seq")
                if (ok) {
                    waitingPong = false
                    watchdogHandler.removeCallbacks(watchdogTimeoutRunnable)
                }
            }
        } catch (_: Throwable) {
            waitingPong = false
            watchdogHandler.removeCallbacks(watchdogTimeoutRunnable)
            if (!seenOrbOnMedia) {
                softRestartWebView(keepUrl = true)
            }
        }
    }

    private fun softRestartWebView(keepUrl: Boolean) {
        if (!this::web.isInitialized) return

        val parent = web.parent as? ViewGroup ?: return
        val urlToLoad = if (keepUrl) (web.url ?: getKioskUrl()) else getKioskUrl()

        try {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.clearHistory()
            web.clearCache(true)

            web.onPause()
            web.pauseTimers()
            web.removeAllViews()

            parent.removeView(web)
            web.destroy()
        } catch (_: Throwable) {}

        val newWeb = WebView(this)
        newWeb.id = R.id.webView

        parent.addView(
            newWeb,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        web = newWeb

        // новая сессия — снова считаем первую страницу splash
        firstPage = true
        lastUrl = null

        configureWebView(web)
        web.settings.textZoom = 100

        try {
            web.resumeTimers()
            web.onResume()
        } catch (_: Throwable) {}

        web.loadUrl(urlToLoad)

        waitingPong = false
        watchdogHandler.removeCallbacks(watchdogTimeoutRunnable)

        installTopSwipeBlocker(forceRecreate = false)
        applySystemGestureExclusion()
    }

    // ---------------------------
    // System UI
    // ---------------------------
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
            installTopSwipeBlocker(forceRecreate = false)
            applySystemGestureExclusion()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(adminHoldRunnable)
        watchdogHandler.removeCallbacks(watchdogPingRunnable)
        watchdogHandler.removeCallbacks(watchdogTimeoutRunnable)
        waitingPong = false
        removeTopSwipeBlocker()

        try {
            if (this::web.isInitialized) {
                web.stopLoading()
                web.loadUrl("about:blank")
                web.clearHistory()
                web.clearCache(true)
                web.onPause()
                web.pauseTimers()
                web.removeAllViews()
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
        } catch (_: Throwable) {}

        super.onDestroy()
    }

    // ---------------------------
    // Helpers
    // ---------------------------
    private fun isOrbBlocked(error: WebResourceError?): Boolean {
        val desc = error?.description?.toString().orEmpty()
        return desc.contains("ERR_BLOCKED_BY_ORB", ignoreCase = true)
    }

    private fun isMediaRequest(req: WebResourceRequest?): Boolean {
        val u = req?.url?.toString().orEmpty()
        return u.endsWith(".webm", true)
                || u.endsWith(".mp4", true)
                || u.endsWith(".m3u8", true)
                || u.endsWith(".ts", true)
    }

    companion object {
        private const val DEFAULT_URL = "https://prod.inno-clouds.ru/kiosk/"
    }
}