@file:Suppress("unused")

package com.example.ytube

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
import androidx.core.graphics.toColorInt


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
 *		android:hardwareAccelerated="true"
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
 *		<style name="Theme.WebApp" parent="android:Theme.Material.NoActionBar">
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
	}



	class EdgeToEdge {
		class StatusBar {
			var blur = true
			var height = 0
			var colorMode = ETE_AUTO
		}

		class NavigationBar {
			var blur = true
			var height = 0
			var colorMode = ETE_AUTO
		}

		/**
		 *	// A "height" nem írható felül!
		 */
		val statusBar = StatusBar()
		/**
		 *	// A "height" nem írható felül!
		 */
		val navigationBar = NavigationBar()
	}



	class BlueTooth(private val act: ComponentActivity) {
		private var bluetoothEventCallback: ((name: String) -> Unit)? = null

		fun connectedName(callback: ((name: String) -> Unit)? = null) {
			callback?.let { bluetoothEventCallback = it }

			val bluetoothManager = act.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
			val adapter = bluetoothManager?.adapter

			adapter?.let { adt ->
				if (adt.isEnabled) {
					try {
						if (ActivityCompat.checkSelfPermission(act, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
							adt.bondedDevices.forEach { device ->
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
		private set
	val dpiScale = activity.resources.displayMetrics.density
	private lateinit var webViewParams: WindowManager.LayoutParams

	/**
	 *	// Csak EDGE_TO_EDGE stílusban, kezdéskor,
	 *	// az API betöltése után és elforgatáskor:
	 *	androidEdgeToEdge(jsTopHeight, jsBottomHeight);
	 */
	lateinit var javaScript: JsClass
		private set
	lateinit var edgeToEdge: EdgeToEdge
		private set
	lateinit var innerWebView: WebView
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
		return activity.assets.open(file).bufferedReader().readText()
	}



	@SuppressLint(
		"SetJavaScriptEnabled",
		"JavascriptInterface",
		"SourceLockedOrientationActivity",
		"ClickableViewAccessibility"
	)
	fun create(): WebApp {

		// Nagyméretű ablaknál:
		if (windowStyle > STYLE_NORMAL) {
			if (windowStyle == STYLE_FULL_SCREEN) fullScreen(true)
			else {
				val s = SystemBarStyle.dark(Color.TRANSPARENT)
				activity.enableEdgeToEdge(statusBarStyle = s, navigationBarStyle = s)
			}
		}

		// Context téma alapján:
		val appContext = (
			if (windowType == TYPE_NORMAL)
				activity
			else
				android.view.ContextThemeWrapper(activity.applicationContext, R.style.Theme_WebApp)
		)

		// WebView beállítása:
		innerWebView = WebView(appContext).apply {
			keepScreenOn = true
			scrollBarSize = 0
			alpha = 0f
			settings.apply {
				javaScriptEnabled = true
				domStorageEnabled = true
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
			setLayerType(View.LAYER_TYPE_HARDWARE, null)

			if (windowStyle == STYLE_EDGE_TO_EDGE) setOnTouchListener { _, event ->
				if (event.action == MotionEvent.ACTION_UP) edgeToEdgeBarColors()
				false
			}
		}

		// Osztályok:
		bluetooth = BlueTooth(activity)
		javaScript = JsClass(innerWebView, dpiScale)
		edgeToEdge = EdgeToEdge()
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
		}, ContextCompat.RECEIVER_EXPORTED)



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

			edgeToEdge.statusBar.height = if (statusHeight == 0f) 0 else (statusHeight / dpiScale).toInt()
			edgeToEdge.navigationBar.height = if (navigHeight == 0f) 0 else (navigHeight / dpiScale).toInt()
		}

		val updateJS = Runnable {
			innerWebView.evaluateJavascript("window.setStatusBarAndNavigationBarHeight(${edgeToEdge.statusBar.height}, ${edgeToEdge.navigationBar.height});", null)
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
						edgeToEdge.statusBar.apply {
							blur = false
							height = 0
						}
						edgeToEdge.navigationBar.apply {
							blur = false
							height = 0
						}
					}

					/* Ez csak kísérletezéshez kell:*/
					//defaultAPI = readAssetFile("barHeights.js")

					defaultAPI = defaultAPI
						.replace(
							"obj = { topBlur:true, btmBlur:true, topHeight:50, btmHeight:100 }",
							"obj = { topBlur:${edgeToEdge.statusBar.blur}, btmBlur:${edgeToEdge.navigationBar.blur}, topHeight:${edgeToEdge.statusBar.height}, btmHeight:${edgeToEdge.navigationBar.height} }"
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
					// Az androidEdgeToEdge(topH, btmH) is lefut !
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

		if (innerWebView.width > 0 && innerWebView.height > 0 &&
			(edgeToEdge.statusBar.colorMode == ETE_AUTO || edgeToEdge.navigationBar.colorMode == ETE_AUTO)) {

			val scrollX = innerWebView.scrollX.toFloat()
			val scrollY = innerWebView.scrollY.toFloat()
			val xOffset = -(scrollX + 300f)
			val targetHeight = innerWebView.height

			if (sampleBitmap == null || sampleBitmap?.height != targetHeight) {
				sampleBitmap = createBitmap(1, targetHeight, Bitmap.Config.RGB_565)
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

				when (edgeToEdge.statusBar.colorMode) {
					ETE_LIGHT -> windowInsetsController.isAppearanceLightStatusBars = false
					ETE_DARK -> windowInsetsController.isAppearanceLightStatusBars = true
					else -> windowInsetsController.isAppearanceLightStatusBars = (topColorLum > 0.5)
				}

				when (edgeToEdge.navigationBar.colorMode) {
					ETE_LIGHT -> windowInsetsController.isAppearanceLightNavigationBars = false
					ETE_DARK -> windowInsetsController.isAppearanceLightNavigationBars = true
					else -> windowInsetsController.isAppearanceLightNavigationBars = (btmColorLum > 0.5)
				}
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

		// Törölni kell minden időzítőt !
		MAIN_LOOPER.removeCallbacksAndMessages(null)

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
		const val ETE_AUTO = 0
		const val ETE_LIGHT = 1
		const val ETE_DARK = 2
	}
}
// ----------- WebApp -----------












object MediaEvent {
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
	private var firstResumeSkipped = false
	private var screenOn = false

	private fun sendToService(keyCode: Int) {
		if (WebAppPlaybackService.state < WebAppPlaybackService.STARTED) return
		val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
		intent.component = ComponentName(activity, WebAppPlaybackService::class.java)
		intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
		activity.startService(intent)
	}

	fun params(params: WindowManager.LayoutParams) {
		parameters = params
	}

	/**
	 *	MediaEvent.START   -> { "onStart".log() }
	 *	MediaEvent.PLAY    -> { "onPlay".log() }
	 *	MediaEvent.PAUSE   -> { "onPause".log() }
	 *	MediaEvent.PREV    -> { "onPrev".log() }
	 *	MediaEvent.NEXT    -> { "onNext".log() }
	 *	MediaEvent.CHANGED -> { "onChanged".log() }
	 *	MediaEvent.STOP    -> { "onStop".log() }
	 */
	fun onEvent(callback: (event: Int) -> Unit) {
		onEventsCallback = callback
	}

	// Ez csak a receiverből fut:
	fun started() {
		onEventsCallback?.invoke(MediaEvent.START)
	}


	// Service indítása, onCreate() x1 + onStartCommand() mindig
	fun start(title: String) {
		activity.startForegroundService(
			Intent(activity, WebAppPlaybackService::class.java).apply {
				putExtra("EXTRA_MEDIA_TITLE", title)
			}
		)
	}

	// Leállítás, onDestroy()
	fun stop(destroyed: Boolean) {
		if (WebAppPlaybackService.state < WebAppPlaybackService.CHANGED) return
		onEventsCallback?.invoke(MediaEvent.STOP)
		// Muszáj ez, mert a szerviz újra kiküldheti az értesítést, a pause miatt ! #3452453443
		WebAppPlaybackService.state = when (destroyed) {
			true -> WebAppPlaybackService.DESTROYED
			false -> WebAppPlaybackService.STOPPED
		}
		// Utána leállítjuk a Service-t:
		activity.stopService(
			Intent(activity, WebAppPlaybackService::class.java)
		)
	}

	fun play(fromService: Boolean? = false) {
		// Service:
		if (fromService == true) onEventsCallback?.invoke(MediaEvent.PLAY)
		// Activity:
		else sendToService(KeyEvent.KEYCODE_MEDIA_PLAY)
	}

	fun pause(fromService: Boolean? = false) {
		if (fromService == true) onEventsCallback?.invoke(MediaEvent.PAUSE)
		else sendToService(KeyEvent.KEYCODE_MEDIA_PAUSE)
	}

	fun prev(fromService: Boolean? = false) {
		if (fromService == true) {
			onEventsCallback?.invoke(MediaEvent.CHANGED)
			onEventsCallback?.invoke(MediaEvent.PREV)
		}
		else sendToService(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
	}

	fun next(fromService: Boolean? = false) {
		if (fromService == true) {
			onEventsCallback?.invoke(MediaEvent.CHANGED)
			onEventsCallback?.invoke(MediaEvent.NEXT)
		}
		else sendToService(KeyEvent.KEYCODE_MEDIA_NEXT)
	}

	fun changeTo(state: String) {
		when (state) {
			"play"	-> sendToService(KeyEvent.KEYCODE_MEDIA_PLAY)
			"pause"	-> sendToService(KeyEvent.KEYCODE_MEDIA_PAUSE)
			"prev"	-> sendToService(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
			"next"	-> sendToService(KeyEvent.KEYCODE_MEDIA_NEXT)
			"stop"	-> stop(false)
		}
	}


	fun topResumedActivityChange(isTopResumedActivity: Boolean) {
		if (Settings.canDrawOverlays(activity)) parameters?.let { params ->
			// onResume:
			if (isTopResumedActivity) {
				// "Elforgatás a háttérben" miatt:
				val display = activity.windowManager.currentWindowMetrics.bounds
				params.width = display.width()
				params.height = display.height()
				params.flags = params.flags and (
						WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
						WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
				).inv()

				activity.windowManager.updateViewLayout(webview, parameters)
				webview.requestFocus()
				// App megnyitásakor még ne fusson le az onLoad miatt !
				if (firstResumeSkipped) {
					// Zárt képernyő utáni visszatéréskor:
					if (!screenOn) webview.alpha = 1f
					else webview.animate()?.alpha(1f)?.setDuration(200)?.startDelay = 200
				}
				// Első onResume átugrása:
				else firstResumeSkipped = true
			}
			// OnPause:
			else {
				screenOn = powerManager.isInteractive

				params.flags = params.flags or
						WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
						WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

				activity.windowManager.updateViewLayout(webview, parameters)
				webview.animate()?.alpha(0f)?.setDuration(200)?.startDelay = 0
			}
		}
	}

	fun destroy() {
		stop(true)
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
	private val inactiveColor = "#999999".toColorInt()
	private var played = false

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

			mediaSession?.player?.let { player ->
				setViewVisibility(R.id.media_play, if (player.isPlaying) View.GONE else View.VISIBLE)
				setViewVisibility(R.id.media_pause, if (player.isPlaying) View.VISIBLE else View.GONE)
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
			} else {
				MAIN_LOOPER.removeCallbacks(updateNotificationRunnable)
				cancel(notificationId)
			}
		}
	}

	private val updateNotificationRunnable = Runnable {
		updateNotification(true)
	}

	// Elhúzáskor visszaállítás, mert az setOngoing nem működik !
	private val notificationDismissReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			if (state >= STARTED && played) updateNotification(true)
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

		"MediaService, onCreate()".log()

		// Értesítés regisztráció:
		val channel = NotificationChannel(channelId, "MediaPlayback", NotificationManager.IMPORTANCE_LOW)
		notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
		notificationManager?.createNotificationChannel(channel)

		// SmallIcon:
		val size = (12 * resources.displayMetrics.density).toInt()
		val bitmap = createBitmap(size, size)
		val canvas = Canvas(bitmap)
		val paint = Paint().apply {
			color = Color.WHITE
			isAntiAlias = true
		}
		canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)
		smallIcon = IconCompat.createWithBitmap(bitmap)

		// Nyil színek:
		activeColor = if (isSystemLightMode(this)) "#333333".toColorInt() else "#eeeeee".toColorInt()

		listener = object : Player.Listener {
			override fun onIsPlayingChanged(isPlaying: Boolean) {
				super.onIsPlayingChanged(isPlaying)
				// STOPPED + CHANGED #3452453443
				if (state < STARTED) return
				// ACTIVE #34534535
				if (state == ACTIVE) {
					when (isPlaying) {
						true  -> sendToWebApp(WebApp.SERVICE_MEDIA_PLAY)
						false -> sendToWebApp(WebApp.SERVICE_MEDIA_PAUSE)
					}
				}
				// Ha elindult a service, várakozás a játszásra:
				else if (isPlaying) state = ACTIVE
				// STARTED + ACTIVE
				played = isPlaying
				updateNotification(true)
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

	} // onCreate


	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		val mediaTitle = intent?.getStringExtra("EXTRA_MEDIA_TITLE") ?: return START_STICKY

		state = STARTED

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

		sendToWebApp(WebApp.SERVICE_MEDIA_START)

		"MediaService, onStartCommand()".log()
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

		"MediaService, ${if (state == DESTROYED) "onDestroy()" else "stopped"}".log()
		super.onDestroy()
	}

	companion object {
		const val DESTROYED = 0
		const val STOPPED   = 1
		const val CHANGED   = 2
		const val STARTED   = 3
		const val ACTIVE    = 4
		var state = DESTROYED
	}
}






private var defaultAPI = """
(function(wnd, doc, edgeToEdge = true) {

	if (wnd.newWebView) return;
	wnd.newWebView = 1;

	if (typeof Object.prototype.params !== 'function')
		Object.defineProperty(Object.prototype, 'params', {
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

	if (typeof Element.prototype.apply !== 'function')
		Object.defineProperty(Element.prototype, 'apply', {
			value: function(block) {
				if (typeof block === 'function') {
					block.call(this, this);
				}
				return this;
			},
			writable: true,
			configurable: true
		});



	const meta = doc.createElement('meta').params({
		name: 'viewport',
		content: 'width=device-width, initial-scale=1.0, user-scalable=no'
	});

	doc.head.appendChild(meta);


	wnd.addEventListener('touchmove', (e) => {
		if (e.touches[0].clientY > (wnd.screen.height * 0.93)) e.preventDefault();
	}, { passive: false });


	if (edgeToEdge) {

		const obj = { topBlur:true, btmBlur:true, topHeight:50, btmHeight:100 };

		wnd.setStatusBarAndNavigationBarHeight = function(topH, btmH) {

			if (obj.topBlur) doc.getElementById('android-top-blurred-overlay').style.height = topH + 'px';
			if (obj.btmBlur) doc.getElementById('android-btm-blurred-overlay').style.height = btmH + 'px';

			if (typeof androidEdgeToEdge === 'function') androidEdgeToEdge(topH, btmH);
		};

		if (obj.topBlur || obj.btmBlur) {
			const css = 'position:fixed; width: 100vw; left:0; backdrop-filter:blur(10px); z-index:2000;';

			const blurredTop = doc.createElement('div').apply((it) => {
				it.id = 'android-top-blurred-overlay';
				it.setAttribute('style', css + 'top:0;');
			});

			const blurredBtm = doc.createElement('div').apply((it) => {
				it.id = 'android-btm-blurred-overlay';
				it.setAttribute('style', css + 'bottom:0;');
			});

			doc.body.appendChild(blurredTop);
			doc.body.appendChild(blurredBtm);
		}

		wnd.setStatusBarAndNavigationBarHeight(obj.topHeight, obj.btmHeight);
	}

})(window, document);
""".trimIndent()




/*

	notification.xml layout:

<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="50dp"
    tools:ignore="TextSizeCheck">

    <LinearLayout
        style="?android:attr/buttonBarStyle"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:layout_marginTop="-5dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        tools:ignore="VisualLintBounds">

        <Button
            android:id="@+id/media_prev"
            style="?android:attr/buttonBarButtonStyle"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:paddingTop="0dp"
            android:paddingBottom="5dp"
            android:text="⏮"
            android:textSize="25sp"
            tools:ignore="HardcodedText,VisualLintButtonSize,VisualLintSystemUi" />

        <Button
            android:id="@+id/media_play"
            style="?android:attr/buttonBarButtonStyle"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:paddingTop="0dp"
            android:paddingBottom="5dp"
            android:text="▶"
            android:textSize="30sp"
            tools:ignore="HardcodedText,VisualLintButtonSize,VisualLintSystemUi" />

        <Button
            android:id="@+id/media_pause"
            style="?android:attr/buttonBarButtonStyle"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:paddingTop="0dp"
            android:paddingBottom="0dp"
            android:text="II"
            android:textFontWeight="1000"
            android:textSize="25sp"
            tools:ignore="HardcodedText,VisualLintButtonSize,VisualLintSystemUi" />

        <Button
            android:id="@+id/media_next"
            style="?android:attr/buttonBarButtonStyle"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:paddingTop="0dp"
            android:paddingBottom="5dp"
            android:text="⏭"
            android:textSize="25sp"
            tools:ignore="HardcodedText,VisualLintButtonSize,VisualLintSystemUi" />

    </LinearLayout>

    <LinearLayout
        android:id="@+id/bottom_line"
        android:layout_width="match_parent"
        android:layout_height="3dp"
        android:layout_alignParentBottom="true"
        android:background="#CC3333"
        android:orientation="horizontal" />

</RelativeLayout>

*/
