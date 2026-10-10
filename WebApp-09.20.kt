@file:Suppress("unused")

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.withSave
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
import androidx.media3.common.C.USAGE_MEDIA
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface


//-------------------------------------------------------------------------->


val MAIN_LOOPER = Handler(Looper.getMainLooper())

fun Any.log() { Log.i("INFO:CONSOLE", this.toString()) }


//-------------------------------------------------------------------------->


fun View.moveX(leftPx: Int, ms: Long) {
	ObjectAnimator.ofFloat(this, "translationX", leftPx.toFloat()).apply {
		duration = ms
		start()
	}
}



fun isSystemLightMode(context: Context): Boolean {
	return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
}


fun webResourceRequestBlock(): WebResourceResponse = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))


//-------------------------------------------------------------------------->


fun WebView.touch(leftScreenPx: Int, topScreenPx: Int) {
	val millis: Long = SystemClock.uptimeMillis()
	this.dispatchTouchEvent(MotionEvent.obtain(millis, millis, MotionEvent.ACTION_DOWN, leftScreenPx.toFloat(), topScreenPx.toFloat(), 0))
	this.dispatchTouchEvent(MotionEvent.obtain(millis, millis + 100, MotionEvent.ACTION_UP, leftScreenPx.toFloat(), topScreenPx.toFloat(), 0))
}


//-------------------------------------------------------------------------->



/**
build.gradle.kts, TYPE_MEDIA

 *	implementation("androidx.media3:media3-session:1.11.1")
 *	implementation("androidx.media3:media3-exoplayer:1.11.1")

Manifest

 *	<uses-permission android:name="android.permission.INTERNET" />
 *	<uses-permission android:name="android.permission.BLUETOOTH" />
 *	<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 *	// TYPE_MEDIA
 *	<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
 *	<uses-permission android:name="android.permission.WAKE_LOCK" />
 *	<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *	<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
 *	<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

 *	<application>
 *		<activity
 *			// Nem reagál ezekre:
 *			android:configChanges="orientation|screenSize|keyboardHidden"
 *			android:windowSoftInputMode="adjustNothing"
 *		</activity>

 *		// TYPE_MEDIA
 *		<service
 *			android:name=".WebAppPlaybackService"
 *			android:exported="false"
 *			android:foregroundServiceType="mediaPlayback">
 *			<intent-filter>
 *				<action android:name="androidx.media3.session.MediaSessionService" />
 *			</intent-filter>
 *		</service>

Theme.xml

 *	<resources>
 *		<style name="Theme.Absolute" parent="android:Theme.Material.NoActionBar">
 *			<item name="android:statusBarColor">@color/graphite</item>
 *			<item name="android:windowBackground">@color/graphite</item>
 *			<item name="android:navigationBarColor">@color/graphite</item>
 *		</style>
 *	</resources>
 */
class WebApp(
	private val activity: ComponentActivity,
	private var windowType: Int = TYPE_NORMAL,
	private var windowStyle: Int = STYLE_NORMAL
) {

	class JsClass(private val webview: WebView, private val dpi: Float) {
		private var commandCallback: ((id: String, params: List<String>) -> Unit)? = null

		val javascriptInterface = object {
			@JavascriptInterface
			fun command(id: String, value: String) {
				MAIN_LOOPER.post {
					val params = value.split(",")
						.map { it.trim() }
						.filter { it.isNotEmpty() }
					commandCallback?.invoke(id, params)
				}
			}
		}

		/**
		 *	// Az onStart után, az onLoad előtt adja hozzá !
		 *	// Ez után fut az androidEdgeToEdge(top, bottom);
		 */
		var addAPI = ""

		/**
		 *	//JavaScript:
		 *	window.android.command('my', '433, true');
		 */
		fun onCommand(callback: (id: String, params: List<String>) -> Unit) {
			commandCallback = callback
		}

		/**
		 *	// Array is küldhető:
		 *	javaScript.send("[screen.width, screen.height]") {
		 *		val arr = it.split(",")
		 *		arr[0].log()
		 *		arr[1].log()
		 *	}
		 */
		fun send(js: String, callback: ((String) -> Unit)? = null) {
			webview.post {
				val script = if (js.startsWith("[") && js.endsWith("]")) "$js.join(',')" else js
				webview.evaluateJavascript(script) { result ->
					result?.let { res ->
						callback?.invoke(res.replace("\"", ""))
					}
				}
			}
		}

		/**
		 *	// Érintés a JavaScript pixelein
		 */
		fun touch(jsLeft: Int, jsTop: Int) {
			val left = (jsLeft * dpi).toInt()
			val top = (jsTop * dpi).toInt()
			webview.touch(left, top)
		}

		class StatusBar {
			var enabled = true
			var blur = true
			var height = 0
		}

		class NavigationBar {
			var enabled = true
			var blur = true
			var height = 0
		}

		val statusBar = StatusBar()
		val navigationBar = NavigationBar()
	}



	class BlueTooth(private val act: ComponentActivity) {
		private var bluetoothEventCallback: ((String) -> Unit)? = null

		fun connectedName(callback: ((name: String) -> Unit)? = null) {
			callback?.let { bluetoothEventCallback = it }

			val bluetoothManager = act.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
			val adapter = bluetoothManager?.adapter

			if (adapter != null && adapter.isEnabled) {
				try {
					if (ActivityCompat.checkSelfPermission(act, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
						adapter.bondedDevices?.forEach { device ->
							val isConnectedMethod = device.javaClass.getMethod("isConnected")
							val isConnected = isConnectedMethod.invoke(device) as Boolean

							if (isConnected) {
								val deviceName = try { device.name } catch (e: Exception) { device.address }
								bluetoothEventCallback?.invoke(deviceName)
								return
							}
						}
				} catch (e: Exception) {}
			}

			bluetoothEventCallback?.invoke("none")
		}
	}

	private val receiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			when (intent?.action) {
				BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
					bluetooth.connectedName()
				}
				SERVICE_MEDIA_PLAY -> {
					media.play(true)
				}
				SERVICE_MEDIA_PAUSE -> {
					media.pause(true)
				}
				SERVICE_MEDIA_PREV -> {
					media.prev(true)
				}
				SERVICE_MEDIA_NEXT -> {
					media.next(true)
				}
				SERVICE_MEDIA_START -> {
					media.started()
				}
			}
		}
	}

	// Protected ------------------------------->
	var display = activity.windowManager.currentWindowMetrics.bounds
	val dpiScale = activity.resources.displayMetrics.density
	private lateinit var webViewParams: WindowManager.LayoutParams
	lateinit var innerWebView: WebView
		private set
	lateinit var javaScript: JsClass
		private set
	lateinit var bluetooth: BlueTooth
		private set
	lateinit var media: WebAppMedia
		private set

	// Instance -------------------------------->
	var ovarlayPermissionAllowed = false
	var windowOrientation: Int = ORIENTATION_AUTO
		set(value) {
			field = value
			activity.requestedOrientation = when (value) {
				ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
				ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
				else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
			}
		}

	/**
	 *	// Érintés a kijelző pixelein
	 */
	fun touch(ktLeft: Int, ktTop: Int) {
		innerWebView.touch(ktLeft, ktTop)
	}

	fun networkGateway(): String {
		val address = NetworkInterface.getNetworkInterfaces()
			?.asSequence()
			?.filter { it.name.contains("lan") }
			?.flatMap { it.inetAddresses.asSequence() }
			?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }

		return address?.hostAddress.toString()
	}

	fun fullScreen(mode: Boolean) {
		val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
		if (mode) {
			controller.hide(WindowInsetsCompat.Type.systemBars())
			controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		} else {
			controller.show(WindowInsetsCompat.Type.systemBars())
			controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
		}
	}

	fun readAssetFile(file: String): String {
		return activity.assets.open(file).bufferedReader().use { it.readText() }
	}



	@SuppressLint(
		"SetJavaScriptEnabled",
		"JavascriptInterface",
		"SourceLockedOrientationActivity",
		"ClickableViewAccessibility"
	)
	fun create(): WebApp {

		// Törli a Destroy exit-et !
		MAIN_LOOPER.removeCallbacksAndMessages(null)

		// Nagyméretű ablaknál:
		if (windowStyle > STYLE_NORMAL) {
			if (windowStyle == STYLE_FULL_SCREEN) fullScreen(true)
			else {
				val s = SystemBarStyle.dark(Color.TRANSPARENT)
				activity.enableEdgeToEdge(statusBarStyle = s, navigationBarStyle = s)
			}
		}

		// WebView beállítása:
		innerWebView = WebView(if (windowType == TYPE_NORMAL) activity else activity.applicationContext).apply {
			keepScreenOn = true
			scrollBarSize = 0
			alpha = 0f
			settings.apply {
				javaScriptEnabled = true
				domStorageEnabled = true
				allowFileAccess = true
				loadsImagesAutomatically = true
				blockNetworkImage = false
				mediaPlaybackRequiresUserGesture = false
				useWideViewPort = false
				loadWithOverviewMode = false
			}
			webChromeClient = object : WebChromeClient() {
				override fun getDefaultVideoPoster(): Bitmap {
					return createBitmap(1,1)
				}
			}
			setBackgroundColor(Color.TRANSPARENT)

			if (windowStyle == STYLE_EDGE_TO_EDGE) setOnTouchListener { _, event ->
				if (event.action == MotionEvent.ACTION_UP) edgeToEdgeBarColors()
				false
			}
		}

		// Osztályok:
		bluetooth = BlueTooth(activity)
		javaScript = JsClass(innerWebView, dpiScale)
		media = WebAppMedia(activity, innerWebView)
		innerWebView.addJavascriptInterface(javaScript.javascriptInterface, "android")

		// Bluetooth:
		if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 100)
		}

		// Engedély ellenőrzése MEDIA-ban:
		ovarlayPermissionAllowed = windowType == TYPE_NORMAL || Settings.canDrawOverlays(activity)

		// Ha nincs engedély:
		if (!ovarlayPermissionAllowed) {
			MAIN_LOOPER.postDelayed({
				if (!activity.isFinishing && !activity.isDestroyed) {
					activity.startActivity(
						Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
						"package:${activity.packageName}".toUri())
					)
				}
			}, 1000)

			return this
		}

		// Bluetooth register csak ha van overlay engedély!!
		ContextCompat.registerReceiver(activity, receiver, IntentFilter().apply {
			addAction(SERVICE_MEDIA_PLAY)
			addAction(SERVICE_MEDIA_PAUSE)
			addAction(SERVICE_MEDIA_PREV)
			addAction(SERVICE_MEDIA_NEXT)
			addAction(SERVICE_MEDIA_START)
			addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
			addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
		}, ContextCompat.RECEIVER_NOT_EXPORTED)



		// TYPE_NORMAL
		if (windowType == TYPE_NORMAL) {
			val param = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT
			)

			// WebView hozzáadás:
			activity.addContentView(innerWebView, param)
		}
		// TYPE_MEDIA
		else {

			// Értesítés engedély:
			if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
				ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
			}

			webViewParams = WindowManager.LayoutParams().apply {
				type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
				format = PixelFormat.TRANSLUCENT
				gravity = (Gravity.TOP or Gravity.START)
				flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

				// NORMAL stílus:
				if (windowStyle == STYLE_NORMAL) {
					width = WindowManager.LayoutParams.MATCH_PARENT
					height = WindowManager.LayoutParams.MATCH_PARENT
				}
				// Nagyméretű ablak:
				else {
					flags = (flags or
							WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
							WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

					fitInsetsTypes = 0
					layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
					width = display.width()
					height = display.height()
				}
			}

			// WebView hozzáadás:
			activity.windowManager.addView(innerWebView, webViewParams)

			media.params(webViewParams)
		}


		var started = false
		fun updateHeights(insets: WindowInsetsCompat) {
			val statusHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top.toFloat()
			val navigHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom.toFloat()

			javaScript.statusBar.height = if (statusHeight == 0f) 0 else (statusHeight / dpiScale).toInt()
			javaScript.navigationBar.height = if (navigHeight == 0f) 0 else (navigHeight / dpiScale).toInt()
		}

		val updateJS = Runnable {
			innerWebView.evaluateJavascript("window.setStatusBarAndNavigationBarHeight(${javaScript.statusBar.height}, ${javaScript.navigationBar.height});", null)
		}

		// Rendszersávok méretei:
		ViewCompat.setOnApplyWindowInsetsListener(innerWebView) { _, insets ->
			// Ha van magasság:
			if (innerWebView.height > 0) {

				if (!started) {

					if (windowStyle == STYLE_EDGE_TO_EDGE) {
						updateHeights(insets)
					}
					else {
						javaScript.statusBar.apply {
							enabled = false
							height = 0
							blur = false
						}
						javaScript.navigationBar.apply {
							enabled = false
							height = 0
							blur = false
						}
					}

					/* Ez csak kísérletezéshez kell:*/
					//defaultAPI = readAssetFile("barHeights.js")

					defaultAPI = defaultAPI
						.replace(
							"obj = { topEnabled:true, btmEnabled:true, topHeight:50, btmHeight:100, topBlur:true, btmBlur:true }",
							"obj = { topEnabled:${javaScript.statusBar.enabled}, btmEnabled:${javaScript.navigationBar.enabled}, topHeight:${javaScript.statusBar.height}, btmHeight:${javaScript.navigationBar.height}, topBlur:${javaScript.statusBar.blur}, btmBlur:${javaScript.navigationBar.blur} }"
						)
						.replace(
							"edgeToEdge = true",
							"edgeToEdge = ${(windowStyle == STYLE_EDGE_TO_EDGE)}"
						)

					started = true
				}
				// Elforgatáskor:
				else if (windowType == TYPE_MEDIA && windowStyle > STYLE_NORMAL) {
					display = activity.windowManager.currentWindowMetrics.bounds
					if (webViewParams.width != display.width()) {
						webViewParams.apply {
							width = display.width()
							height = display.height()
						}
						activity.windowManager.updateViewLayout(innerWebView, webViewParams)
					}
					updateHeights(insets)
					// Magasságok frissítése és újraküldése
					// Az onAndroid(topH, btmH) is lefut !
					if (windowStyle == STYLE_EDGE_TO_EDGE) {
						MAIN_LOOPER.removeCallbacks(updateJS)
						MAIN_LOOPER.postDelayed(updateJS, 100)
					}
				}
			}

			insets
		}

		return this
	}



	private var sampleBitmap: Bitmap? = null
	private var sampleCanvas: Canvas? = null
	private fun edgeToEdgeBarColors() {

		if (innerWebView.width > 0 && innerWebView.height > 0) {

			val scrollX = innerWebView.scrollX.toFloat()
			val scrollY = innerWebView.scrollY.toFloat()
			val xOffset = -(scrollX + 300f)
			val targetHeight = innerWebView.height

			if (sampleBitmap == null || sampleBitmap?.height != targetHeight) {
				sampleBitmap = Bitmap.createBitmap(1, targetHeight, Bitmap.Config.RGB_565)
				sampleCanvas = Canvas(sampleBitmap!!)
			}

			sampleCanvas?.let { canvas ->
				canvas.withSave {
					translate(xOffset, -scrollY)
					innerWebView.draw(this)
				}

				val topColorLum = sampleBitmap!!.getColor(0, 40).luminance()
				val btmColorLum = sampleBitmap!!.getColor(0, targetHeight - 60).luminance()

				val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
				windowInsetsController.isAppearanceLightStatusBars = (topColorLum > 0.5)
				windowInsetsController.isAppearanceLightNavigationBars = (btmColorLum > 0.5)
			}
		}
	}


	/**
	 *	// Láncolható !
	 */
	fun events(
		onStart: ((WebView, String?) -> Unit)? = null,
		onLoad: ((WebView, String?) -> Unit)? = null,
		onError: ((WebView) -> Unit)? = null,
		onIntercept: ((WebView, WebResourceRequest?) -> WebResourceResponse?)? = null,
		onBack: ((WebView) -> Unit)? = null
	): WebApp {

		onBack?.let { action ->
			// NORMAL módban Activity visszagomb
			val callback = object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() {
					action(innerWebView)
				}
			}
			activity.onBackPressedDispatcher.addCallback(activity, callback)

			// MEDIA módban Direct Key Listener
			innerWebView.isFocusable = true
			innerWebView.isFocusableInTouchMode = true
			innerWebView.setOnKeyListener { _, keyCode, event ->
				if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
					action(innerWebView)
					true
				} else {
					false
				}
			}
		}

		innerWebView.webViewClient = object : WebViewClient() {

			var loaded = true
			override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
				super.onPageStarted(view, url, favicon)
				if (!loaded) return
				loaded = false
				view?.let { wv ->
					wv.alpha = 0f
					onStart?.invoke(wv, url)
				}
			}

			override fun onPageFinished(view: WebView?, url: String?) {
				if (loaded) return
				loaded = true
				view?.let { wv ->
					// API küldés:
					wv.evaluateJavascript(javaScript.addAPI + defaultAPI) {
						// OnLoad:
						onLoad?.invoke(wv, url)
						// Bar színek:
						if (windowStyle == STYLE_EDGE_TO_EDGE) edgeToEdgeBarColors()
						// Fade-in
						wv.animate().alpha(1f).setDuration(600).startDelay = 0
					}
				}
			}

			override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
				val customResponse = view?.let { onIntercept?.invoke(it, request) }
				if (customResponse != null) {
					return customResponse
				}
				return super.shouldInterceptRequest(view, request)
			}

			override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
				if (request?.isForMainFrame == true) {
					view?.let { wv ->
						wv.stopLoading()
						onError?.invoke(wv)
					}
				}
			}
		}

		return this
	}



	/**
	 *	// A tartalom lehet:
	 *	""
	 *	"http..."
	 *	"index.html"
	 *	"<html></html>"
	 */
	fun loadContent(content: String) {
		val input = content.trim()
		when {
			input.isEmpty() -> {
				innerWebView.loadUrl("about:blank")
			}
			input.startsWith("http") -> {
				innerWebView.loadUrl(input)
			}
			input.endsWith(".html") -> {
				innerWebView.loadUrl("file:///android_asset/$input")
			}
			else -> {
				innerWebView.loadDataWithBaseURL("file:///android_asset/", input, "text/html", "UTF-8", null)
			}
		}
	}



	fun destroy() {
		if (!ovarlayPermissionAllowed) return

		CookieManager.getInstance().flush()

		try { activity.unregisterReceiver(receiver) } catch (_: Exception) {}

		// Ablakok törlése:
		if (windowType == TYPE_NORMAL) {
			try { (innerWebView.parent as? ViewGroup)?.removeView(innerWebView) } catch (_: Throwable) {}
		}
		else {
			try { activity.windowManager.removeViewImmediate(innerWebView) } catch (_: Throwable) {}

			media.destroy()
		}

		// EdgeToEdge:
		sampleCanvas?.let {
			sampleBitmap?.recycle()
			sampleBitmap = null
			sampleCanvas = null
		}

		// WebView stop:
		innerWebView.apply {
			stopLoading()
			clearHistory()
			removeAllViews()
			removeJavascriptInterface("android")
		}

		// WebView törlés:
		try { innerWebView.destroy() } catch (_: Throwable) {}

		// Maradék:
		MAIN_LOOPER.postDelayed({ Runtime.getRuntime().exit(0) }, 300)
	}

	companion object {
		const val TYPE_NORMAL = 0
		const val TYPE_MEDIA = 1
		const val STYLE_NORMAL = 0
		const val STYLE_EDGE_TO_EDGE = 1
		const val STYLE_FULL_SCREEN = 2
		const val ORIENTATION_AUTO = 0
		const val ORIENTATION_PORTRAIT = 1
		const val ORIENTATION_LANDSCAPE = 2
		const val SERVICE_MEDIA_PLAY = "action_1"
		const val SERVICE_MEDIA_PAUSE = "action_2"
		const val SERVICE_MEDIA_PREV = "action_3"
		const val SERVICE_MEDIA_NEXT = "action_4"
		const val SERVICE_MEDIA_START = "action_5"
	}
}





object MediaEvents {
	const val START		= 0
	const val PLAY		= 1
	const val PAUSE		= 2
	const val PREV		= 3
	const val NEXT		= 4
	const val CHANGED	= 5
	const val STOP		= 6
}

class WebAppMedia(
	private val activity: ComponentActivity,
	private val webview: WebView
) {

	private var parameters: WindowManager.LayoutParams? = null
	private var onEventsCallback: ((event: Int) -> Unit)? = null
	private val powerManager = activity.getSystemService(POWER_SERVICE) as PowerManager
	private var resumedActivity = false
	private var screenOn = false

	private fun sendToService(keyCode: Int) {
		val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
		intent.component = ComponentName(activity, WebAppPlaybackService::class.java)
		intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
		activity.startService(intent)
	}

	fun params(params: WindowManager.LayoutParams) {
		parameters = params
	}

	/**
	 *	MediaEvents.START   -> { "onStart".log() }
	 *	MediaEvents.PLAY    -> { "onPlay".log() }
	 *	MediaEvents.PAUSE   -> { "onPause".log() }
	 *	MediaEvents.PREV    -> { "onPrev".log() }
	 *	MediaEvents.NEXT    -> { "onNext".log() }
	 *	MediaEvents.CHANGED -> { "onChanged".log() }
	 *	MediaEvents.STOP    -> { "onStop".log() }
	 */
	fun onEvents(callback: (event: Int) -> Unit) {
		onEventsCallback = callback
	}

	// onStartCommand:
	fun start(title: String) {
		activity.startForegroundService(
			Intent(activity, WebAppPlaybackService::class.java).apply {
				putExtra("EXTRA_MEDIA_TITLE", title)
			}
		)
	}

	fun stop() {
		onEventsCallback?.invoke(MediaEvents.STOP)
		// Muszáj ez, mert a szerviz újra kiküldheti az értesítést, a pause miatt ! #3452453443
		WebAppPlaybackService.state = WebAppPlaybackService.STOPPED
		// Utána leállítjuk a Service-t:
		activity.stopService(
			Intent(activity, WebAppPlaybackService::class.java)
		)
	}

	fun started() {
		onEventsCallback?.invoke(MediaEvents.START)
	}

	fun play(fromService: Boolean? = false) {
		// Service:
		if (fromService == true) onEventsCallback?.invoke(MediaEvents.PLAY)
		// Activity:
		else sendToService(KeyEvent.KEYCODE_MEDIA_PLAY)
	}

	fun pause(fromService: Boolean? = false) {
		if (fromService == true) onEventsCallback?.invoke(MediaEvents.PAUSE)
		else sendToService(KeyEvent.KEYCODE_MEDIA_PAUSE)
	}

	fun prev(fromService: Boolean? = false) {
		if (fromService == true) {
			onEventsCallback?.invoke(MediaEvents.CHANGED)
			onEventsCallback?.invoke(MediaEvents.PREV)
		}
		else sendToService(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
	}

	fun next(fromService: Boolean? = false) {
		if (fromService == true) {
			onEventsCallback?.invoke(MediaEvents.CHANGED)
			onEventsCallback?.invoke(MediaEvents.NEXT)
		}
		else sendToService(KeyEvent.KEYCODE_MEDIA_NEXT)
	}

	fun topResumedActivityChanged(isTopResumedActivity: Boolean) {
		if (!Settings.canDrawOverlays(activity) || parameters == null) return
		// onResume:
		if (isTopResumedActivity) {
			val display = activity.windowManager.currentWindowMetrics.bounds
			parameters?.let { params ->
				// Elforgatás a háttérben miatt:
				params.width = display.width()
				params.height = display.height()
				params.flags = params.flags and (
						WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
						WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
				).inv()
			}
			activity.windowManager.updateViewLayout(webview, parameters)
			webview.requestFocus()
			// App megnyitásakor még ne fusson le az onLoad miatt !
			if (resumedActivity) {
				// Zárt képernyő utáni visszatéréskor:
				if (!screenOn) webview.alpha = 1f
				else webview.animate()?.alpha(1f)?.setDuration(200)?.startDelay = 200
			}
			else resumedActivity = true
			return
		}
		// OnPause:
		screenOn = powerManager.isInteractive

		parameters?.let { params ->
			params.flags = params.flags or
					WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
					WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
		}

		activity.windowManager.updateViewLayout(webview, parameters)
		webview.animate()?.alpha(0f)?.setDuration(200)?.startDelay = 0
	}

	fun destroy() {
		stop()
		onEventsCallback = null
		parameters = null
	}
}









class WebAppPlaybackService : MediaSessionService() {

	private var mediaSession: MediaSession? = null
	private var notificationManager: NotificationManager? = null
	private var smallIcon: IconCompat? = null
	private var listener: Player.Listener? = null

	private val dismissedAction = "dismissed"
	private val notificationId = 43234
	private val channelId = "WebAppMediaPlayback"
	private var sendedNotify = false
	private var activeColor = 0
	private val inactiveColor = Color.parseColor("#999999")

	private fun sendToWebApp(action: String) {
		sendBroadcast(Intent(action).apply {
			setPackage(packageName)
		})
	}

	private fun sendToService(keyCode: Int): PendingIntent {
		val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
		intent.component = ComponentName(this, WebAppPlaybackService::class.java)
		intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
		return PendingIntent.getService(this, keyCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
	}

	// Blokkoljuk a Media3 értesítését !!!
	override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {}

	// Saját értesítés:
	private fun customNotification(): Notification {
		val customLayout = RemoteViews(packageName, R.layout.notification).apply {

			mediaSession?.player?.let {
				setViewVisibility(R.id.media_play, if (it.isPlaying) View.GONE else View.VISIBLE)
				setViewVisibility(R.id.media_pause, if (it.isPlaying) View.VISIBLE else View.GONE)
			}

			if (state == ACTIVE) {
				setOnClickPendingIntent(R.id.media_play, sendToService(KeyEvent.KEYCODE_MEDIA_PLAY))
				setOnClickPendingIntent(R.id.media_pause, sendToService(KeyEvent.KEYCODE_MEDIA_PAUSE))
				setOnClickPendingIntent(R.id.media_prev, sendToService(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
				setOnClickPendingIntent(R.id.media_next, sendToService(KeyEvent.KEYCODE_MEDIA_NEXT))
				setTextColor(R.id.media_play, activeColor)
				setTextColor(R.id.media_pause, activeColor)
				setTextColor(R.id.media_prev, activeColor)
				setTextColor(R.id.media_next, activeColor)
			}
			else {
				setTextColor(R.id.media_play, inactiveColor)
				setTextColor(R.id.media_pause, inactiveColor)
				setTextColor(R.id.media_prev, inactiveColor)
				setTextColor(R.id.media_next, inactiveColor)
			}
		}

		// Elhúzáskor:
		val setOngoingAlternative = PendingIntent.getBroadcast(this, 100,
			Intent(dismissedAction).apply { setPackage(packageName) },
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		return NotificationCompat.Builder(this, channelId).run {
			setSmallIcon(smallIcon!!)
			setCustomContentView(customLayout)
			setCustomBigContentView(customLayout)
			setStyle(NotificationCompat.DecoratedCustomViewStyle())
			setDeleteIntent(setOngoingAlternative)				// setOngoing alternatíva
			setVisibility(NotificationCompat.VISIBILITY_PUBLIC)	// Záróképernyőn is teljes vezérlés
			setSilent(true)
			build()
		}
	}

	private fun updateNotification(show: Boolean) {
		notificationManager?.apply {
			if (show) {
				if (!sendedNotify) {
					MAIN_LOOPER.removeCallbacks(updateNotificationRunnable)
					notify(notificationId, customNotification())
					MAIN_LOOPER.postDelayed({
						sendedNotify = false
					}, 300)
					sendedNotify = true
				}
				else {
					MAIN_LOOPER.removeCallbacks(updateNotificationRunnable)
					MAIN_LOOPER.postDelayed(updateNotificationRunnable, 300)
				}
			} else cancel(notificationId)
		}
	}

	private val updateNotificationRunnable = Runnable {
		updateNotification(true)
	}

	// Elhúzáskor visszaállítás, mert az setOngoing nem működik !
	private val notificationDismissReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			updateNotification(true)
		}
	}

	private val mediaSessionCallback = object : MediaSession.Callback {
		@UnstableApi
		override fun onMediaButtonEvent(
			session: MediaSession,
			controllerInfo: MediaSession.ControllerInfo,
			intent: Intent
		): Boolean {
			intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)?.let {
				if (it.action == KeyEvent.ACTION_DOWN && state == ACTIVE) {
					// Events:
					when (it.keyCode) {
						KeyEvent.KEYCODE_MEDIA_PLAY -> {
							mediaSession?.player?.play()
							return true
						}
						KeyEvent.KEYCODE_MEDIA_PAUSE -> {
							mediaSession?.player?.pause()
							return true
						}
						KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
							if (state == CHANGED) return true
							state = CHANGED						// 1 #34534535
							mediaSession?.player?.pause()		// 2
							updateNotification(true)
							sendToWebApp(WebApp.SERVICE_MEDIA_PREV)
							return true
						}
						KeyEvent.KEYCODE_MEDIA_NEXT -> {
							if (state == CHANGED) return true
							state = CHANGED
							mediaSession?.player?.pause()
							updateNotification(true)
							sendToWebApp(WebApp.SERVICE_MEDIA_NEXT)
							return true
						}
					}
				}
			}
			return super.onMediaButtonEvent(session, controllerInfo, intent)
		}
	}



	@UnstableApi
	override fun onCreate() {
		super.onCreate()

		"MediaService, onCreate".log()

		// Értesítés regisztráció:
		val channel = NotificationChannel(channelId, "MediaPlayback", NotificationManager.IMPORTANCE_LOW)
		notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
		notificationManager?.createNotificationChannel(channel)

		// SmallIcon:
		val size = (12 * resources.displayMetrics.density).toInt()
		val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val paint = Paint().apply {
			color = Color.WHITE
			isAntiAlias = true
		}
		canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)
		smallIcon = IconCompat.createWithBitmap(bitmap)

		// Nyil színek:
		activeColor = if (isSystemLightMode(this)) Color.parseColor("#333333") else Color.parseColor("#eeeeee")

		listener = object : Player.Listener {
			override fun onIsPlayingChanged(isPlaying: Boolean) {
				super.onIsPlayingChanged(isPlaying)
				// STOPPED + CHANGED #3452453443
				if (state < STARTED) return
				// Ha elindult a service, várakozás a játszásra:
				if (state == STARTED && isPlaying) state = ACTIVE
				// STARTED + ACTIVE #34534535
				updateNotification(true)
				if (isPlaying)	sendToWebApp(WebApp.SERVICE_MEDIA_PLAY)
				else			sendToWebApp(WebApp.SERVICE_MEDIA_PAUSE)
			}
		}

		val attr = AudioAttributes.Builder().run {
			setContentType(AUDIO_CONTENT_TYPE_MUSIC)
			setUsage(USAGE_MEDIA)
			build()
		}

		val player = ExoPlayer.Builder(this).run {
			setWakeMode(C.WAKE_MODE_NETWORK)
			setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
			setAudioAttributes(attr, false)
			build()
		}

		listener?.let(player::addListener)

		mediaSession = MediaSession.Builder(this, player).run {
			setId("WebAppSession:$packageName")
			setCallback(mediaSessionCallback)
			build()
		}

		ContextCompat.registerReceiver(this,
			notificationDismissReceiver,
			IntentFilter(dismissedAction),
			ContextCompat.RECEIVER_NOT_EXPORTED
		)

		startForeground(notificationId, customNotification())
	}


	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		val mediaTitle = intent?.getStringExtra("EXTRA_MEDIA_TITLE") ?: return START_STICKY

		val item = MediaItem.Builder().run {
			setMediaId("noname")
			setUri("asset:///silent.mp3")
			setMediaMetadata(MediaMetadata.Builder().run {
				setTitle(mediaTitle)
				build()
			})
			build()
		}

		mediaSession?.player?.apply {
			setMediaItem(item)
			setPlaybackSpeed(0.1f)
			playWhenReady = true
			prepare()
		}

		state = STARTED

		sendToWebApp(WebApp.SERVICE_MEDIA_START)

		"MediaService, started".log()
		return START_STICKY
	}


	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession!!


	override fun onDestroy() {

		try { unregisterReceiver(notificationDismissReceiver) } catch (_: Exception) {}

		mediaSession?.apply {
			player.apply {
				listener?.let(player::removeListener)
				pause()
				clearMediaItems()
				stop()
				release()
			}
			release()
		}

		updateNotification(false)

		listener = null
		mediaSession = null
		notificationManager = null
		smallIcon = null

		"MediaService, stopped & destroyed".log()
		super.onDestroy()
	}

	companion object {
		const val STOPPED = 1
		const val CHANGED = 2
		const val STARTED = 3
		const val ACTIVE  = 4
		var state = STOPPED
	}
}






private var defaultAPI = """
(function(wnd, doc, edgeToEdge = true) {

	if (wnd.newWebView) return;
	wnd.newWebView = 1;

	/*Object.defineProperty(Object.prototype, 'params', {
		value: function(object) {
			if (object && typeof object === 'object') {
				for (const key in object) {
					if (Object.prototype.hasOwnProperty.call(object, key)) {
						this[key] = object[key];
					}
				}
			}
			return this;
		},
		writable: true,
		configurable: true
	});

	Object.defineProperty(Element.prototype, 'apply', {
		value: function(block) {
			if (typeof block === 'function') {
				block.call(this, this);
			}
			return this;
		},
		writable: true,
		configurable: true
	});*/

	const meta = doc.createElement('meta');
	meta.name = 'viewport';
	meta.content = 'width=device-width, initial-scale=1.0, user-scalable=no';
	doc.head.appendChild(meta);


	wnd.addEventListener('touchmove', (e) => {
		if (e.touches[0].clientY > (wnd.screen.height * 0.93)) e.preventDefault();
	}, { passive: false });


	if (edgeToEdge) {

		const obj = { topEnabled:true, btmEnabled:true, topHeight:50, btmHeight:100, topBlur:true, btmBlur:true };

		wnd.setStatusBarAndNavigationBarHeight = function(topH, btmH) {
			let gradient = 'linear-gradient(to bottom,';

			if (obj.topEnabled && obj.topBlur) gradient += ('black ' + (topH * 0.8) + 'px, transparent ' + topH + 'px');

			if (obj.topEnabled && obj.topBlur && obj.btmEnabled && obj.btmBlur) gradient += ',';

			if (obj.btmEnabled && obj.btmBlur) gradient += ('transparent calc(100% - ' + btmH + 'px), black calc(100% - ' + (btmH * 0.6) + 'px)');

			gradient += ')';

			if (obj.topEnabled || obj.btmEnabled) doc.getElementById('top-bottom-blurred-element').style.maskImage = gradient;

			if (typeof androidEdgeToEdge === 'function') androidEdgeToEdge(topH, btmH);
		};

		if (obj.topEnabled || obj.btmEnabled) {
			const css = (
				'position: fixed;' +
				'content: "";' +
				'inset: 0;' +
				'pointer-events: none;' +
				'backdrop-filter: blur(10px);' +
				'z-index: 10000;');

			const blurredElem = doc.createElement('div');

			blurredElem.id = 'top-bottom-blurred-element';
			blurredElem.setAttribute('style', css);

			doc.body.appendChild(blurredElem);
			wnd.setStatusBarAndNavigationBarHeight(obj.topHeight, obj.btmHeight);
		}
	}

})(window, document);
""".trimIndent()




/*

	notification.xml layout:

<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    style="?android:attr/buttonBarStyle"
    android:layout_width="match_parent"
    android:layout_height="60dp"
    android:orientation="horizontal"
    android:gravity="center_vertical">

    <Button
        android:id="@+id/media_prev"
        style="?android:attr/buttonBarButtonStyle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:text="⏮"
        android:textSize="25sp"
        android:paddingTop="0dp"
        android:paddingBottom="5dp"
        tools:ignore="HardcodedText" />

    <Button
        android:id="@+id/media_play"
        style="?android:attr/buttonBarButtonStyle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:text="▶"
        android:textSize="30sp"
        android:paddingTop="0dp"
        android:paddingBottom="5dp"
        tools:ignore="HardcodedText" />

    <Button
        android:id="@+id/media_pause"
        style="?android:attr/buttonBarButtonStyle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textFontWeight="1000"
        android:text="II"
        android:textSize="25sp"
        tools:ignore="HardcodedText" />

    <Button
        android:id="@+id/media_next"
        style="?android:attr/buttonBarButtonStyle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:text="⏭"
        android:textSize="25sp"
        android:paddingTop="0dp"
        android:paddingBottom="5dp"
        tools:ignore="HardcodedText" />

</LinearLayout>

*/
