@file:Suppress("unused")

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Notification
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
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
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
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

	class JsInterface(private val wv: WebView, private val dpi: Float) {
		private var commandCallback: ((id: String, params: List<String>) -> Unit) = { _,_ -> }

		/**
		 *	// Az onStart után, az onLoad előtt adja hozzá !
		 */
		var addAPI = ""

		/**
		 *	//JavaScript:
		 *	window.android.command('my', '433, true');
		 */
		fun onCommand(callback: ((id: String, params: List<String>) -> Unit)) {
			commandCallback = callback
		}

		@JavascriptInterface
		fun command(id: String, value: String) {
			MAIN_LOOPER.post {
				val params = value.split(",")
					.map { it.trim() }
					.filter { it.isNotEmpty() }
				commandCallback.invoke(id, params)
			}
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
			wv.post {
				val script = if (js.startsWith("[") && js.endsWith("]")) "$js.join(',')" else js
				wv.evaluateJavascript(script) { result ->
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
			wv.touch(left, top)
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
		private var bluetoothEventCallback: ((String) -> Unit) = {}

		fun connectedName(callback: ((name: String) -> Unit) ?= null) {
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
								bluetoothEventCallback.invoke(deviceName)
								return
							}
						}
				} catch (e: Exception) {}
			}

			bluetoothEventCallback.invoke("none")
		}
	}

	private val receiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			when (intent?.action) {
				BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
					bluetooth.connectedName()
				}
				WebAppPlaybackService.ACTION_MEDIA_PLAY -> {
					media.controller.play()
				}
				WebAppPlaybackService.ACTION_MEDIA_PAUSE -> {
					media.controller.pause()
				}
				WebAppPlaybackService.ACTION_MEDIA_PREV -> {
					media.controller.seekTo(0,0)
				}
				WebAppPlaybackService.ACTION_MEDIA_NEXT -> {
					media.controller.seekTo(2,0)
				}
			}
		}
	}


	// Global ---------------------------------->
	companion object {
		const val TYPE_NORMAL = 0
		const val TYPE_MEDIA = 1
		const val STYLE_NORMAL = 0
		const val STYLE_EDGE_TO_EDGE = 1
		const val STYLE_FULL_SCREEN = 2
		const val ORIENTATION_AUTO = 0
		const val ORIENTATION_PORTRAIT = 1
		const val ORIENTATION_LANDSCAPE = 2
	}


	// Protected ------------------------------->
	var display = activity.windowManager.currentWindowMetrics.bounds
	val dpiScale = activity.resources.displayMetrics.density
	private lateinit var webViewParams: WindowManager.LayoutParams
	lateinit var innerWebView: WebView
		private set
	lateinit var javaScript : JsInterface
		private set
	lateinit var bluetooth : BlueTooth
		private set
	lateinit var media : WebAppMedia
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
		javaScript = JsInterface(innerWebView, dpiScale)
		innerWebView.addJavascriptInterface(javaScript, "android")

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
			addAction(WebAppPlaybackService.ACTION_MEDIA_PLAY)
			addAction(WebAppPlaybackService.ACTION_MEDIA_PAUSE)
			addAction(WebAppPlaybackService.ACTION_MEDIA_PREV)
			addAction(WebAppPlaybackService.ACTION_MEDIA_NEXT)
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

			media = WebAppMedia(activity, innerWebView, webViewParams).create()
		}


		var started = false
		// Rendszersávok méretei:
		ViewCompat.setOnApplyWindowInsetsListener(innerWebView) { _, insets ->
			// Ha van magasság:
			if (innerWebView.height > 0) {

				if (!started) {

					if (windowStyle == STYLE_EDGE_TO_EDGE) {
						val statusHeight =
							insets.getInsets(WindowInsetsCompat.Type.statusBars()).top.toFloat()
						val navigHeight =
							insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom.toFloat()

						if (javaScript.statusBar.height == 0)
							javaScript.statusBar.height = (statusHeight / dpiScale).toInt()

						if (javaScript.navigationBar.height == 0 && navigHeight > 0f)
							javaScript.navigationBar.height = (navigHeight / dpiScale).toInt()
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
					//bodyFunction = activity.assets.open("barHeights.js").bufferedReader().use { it.readText() }

					defaultAPI = defaultAPI
						.replace(
							"obj = { topEnabled:true, btmEnabled:true, topHeight:50, btmHeight:100, topBlur:true, btmBlur:true }",
							"obj = { topEnabled:${javaScript.statusBar.enabled}, btmEnabled:${javaScript.navigationBar.enabled}, topHeight:${javaScript.statusBar.height}, btmHeight:${javaScript.navigationBar.height}, topBlur:${javaScript.statusBar.blur}, btmBlur:${javaScript.navigationBar.blur} }"
						)
						.replace(
							"edgeToEdge = false",
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
				view?.alpha = 0f
				view?.let { wv ->
					wv.post {
						onStart?.invoke(wv, url)
					}
				}
			}

			override fun onPageFinished(view: WebView?, url: String?) {
				if (loaded) return
				loaded = true
				view?.animate()?.alpha(1f)?.setDuration(600)?.startDelay = 0
				view?.let { wv ->
					if (windowStyle == STYLE_EDGE_TO_EDGE) {
						edgeToEdgeBarColors()
					}
					wv.post {
						wv.evaluateJavascript(defaultAPI + javaScript.addAPI) {
							onLoad?.invoke(wv, url)
						}
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
					view?.stopLoading()
					view?.let {
						onError?.invoke(it)
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
}







class WebAppMedia(
	private val act: ComponentActivity,
	private val wv: WebView,
	private val wvParams: WindowManager.LayoutParams
) {
	lateinit var controller: MediaController
	var onReady: (() -> Unit) = {}

	private var controllerPlayCallback: (() -> Unit) = {}
	private var controllerPauseCallback: (() -> Unit) = {}
	private var controllerPrevCallback: (() -> Unit) = {}
	private var controllerNextCallback: (() -> Unit) = {}
	private val powerManager = act.getSystemService(POWER_SERVICE) as PowerManager
	private var resumedActivity = false
	private var screenOn = false
	private lateinit var future: ListenableFuture<MediaController>
	private val listener = object : Player.Listener {

		fun change(e: String) {
			events += e
			MAIN_LOOPER.removeCallbacks(ev)
			MAIN_LOOPER.postDelayed(ev, 200)
		}

		var events = ""
		val ev = Runnable {

			if (events == "PLAY") {
				controllerPlayCallback.invoke()
			}
			else if (events == "PAUSE") {
				controllerPauseCallback.invoke()
			}
			else if (events.contains("PREV")) {
				controller.pause()
				controller.seekTo(1,0)
				controllerPrevCallback.invoke()
			}
			else if (events.contains("NEXT")) {
				controller.pause()
				controller.seekTo(1,0)
				controllerNextCallback.invoke()
			}

			events = ""
		}

		override fun onIsPlayingChanged(isPlaying: Boolean) {
			change(if (isPlaying) "PLAY" else "PAUSE")
		}

		override fun onTracksChanged(tracks: Tracks) {
			if (tracks.groups.isEmpty()) return
			when (controller.currentMediaItemIndex) {
				0 -> change("PREV")
				2 -> change("NEXT")
			}
		}
	}

	fun events(
		onPlay: (() -> Unit) ?= null,
		onPause: (() -> Unit) ?= null,
		onPrev: (() -> Unit) ?= null,
		onNext: (() -> Unit) ?= null
	) {
		onPlay?.let { controllerPlayCallback = it }
		onPause?.let { controllerPauseCallback = it }
		onPrev?.let { controllerPrevCallback = it }
		onNext?.let { controllerNextCallback = it }
	}

	fun create(): WebAppMedia {
		val sessionToken = SessionToken(act.applicationContext, ComponentName(act.applicationContext, WebAppPlaybackService::class.java))
		future = MediaController.Builder(act, sessionToken).buildAsync()
		future.addListener({
			controller = future.get()
			controller.addListener(listener)
			// Készen áll a Vezérlő:
			wv.post { onReady.invoke() }
		}, ContextCompat.getMainExecutor(act))
		return this
	}

	/**
	 *	// title: Zene címe
	 *	// background: hexColor, imageURL, default
	 */
	fun setup(title: String, background: String = "") {

		if (!::controller.isInitialized) return

		/*val parsedColor = when {
			background.startsWith("#") -> background.toColorInt()
			isSystemLightMode(act) -> Color.WHITE
			else -> Color.BLACK
		}

		val artworkBytes = ByteArrayOutputStream().use { stream ->
			Bitmap.createBitmap(10, 10, Bitmap.Config.RGB_565).apply {
				eraseColor(parsedColor)
				compress(Bitmap.CompressFormat.JPEG, 80, stream)
				recycle()
			}
			stream.toByteArray()
		}*/

		val noNameItem = MediaItem.Builder().run {
			setMediaId("noname")
			setUri("asset:///silent.mp3")
			setMediaMetadata(MediaMetadata.Builder().run {
				setTitle("...")
				//setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_MEDIA)
				build()
			})
			build()
		}

		val mainItem = MediaItem.Builder().run {
			setMediaId("main")
			setUri("asset:///silent.mp3")
			setMediaMetadata(MediaMetadata.Builder().run {
				setTitle(title)
				// teszt "https://i.ytimg.com/vi/e8_Ddw0H0YA/sddefault.jpg"
				//if (background.startsWith("http")) setArtworkUri(background.toUri())
				//else setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_MEDIA)
				build()
			})
			build()
		}

		controller.apply {
			replaceMediaItem(0, noNameItem)
			replaceMediaItem(1, mainItem)
			replaceMediaItem(2, noNameItem)
			prepare()
			seekTo(1, 0)
			setPlaybackSpeed(0.1f)
			playWhenReady = true
		}
	}



	fun topResumedActivityChanged(isTopResumedActivity: Boolean) {
		if (!Settings.canDrawOverlays(act)) return
		// onResume:
		if (isTopResumedActivity) {
			wvParams.flags = wvParams.flags and (
					WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
					WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
			).inv()
			act.windowManager.updateViewLayout(wv, wvParams)
			wv.requestFocus()
			// App megnyitásakor még ne fusson le az onLoad miatt !
			if (resumedActivity) {
				// Zárt képernyő utáni visszatéréskor:
				if (!screenOn) wv.alpha = 1f
				else wv.animate()?.alpha(1f)?.setDuration(100)?.startDelay = 300
			}
			else resumedActivity = true
			return
		}
		// OnPause:
		screenOn = powerManager.isInteractive

		wvParams.flags = wvParams.flags or
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

		act.windowManager.updateViewLayout(wv, wvParams)
		wv.animate()?.alpha(0f)?.setDuration(200)?.startDelay = 0
	}



	fun destroy() {
		controllerPlayCallback = {}
		controllerPauseCallback = {}
		controllerPrevCallback = {}
		controllerNextCallback = {}
		onReady = {}

		controller.apply {
			removeListener(listener)
			stop()
			release()
		}

		try { MediaController.releaseFuture(future) } catch (_: Throwable) {}
	}
}







class WebAppPlaybackService : MediaSessionService() {

	private lateinit var mediaSession: MediaSession
	private var playing = false

	private fun createNotification(): Notification {
		val customLayout = RemoteViews(packageName, R.layout.notification)

		// PLAY:
		val playPendingIntent = PendingIntent.getBroadcast(this, 100,
			Intent(ACTION_MEDIA_PLAY).apply { setPackage(packageName) },
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		customLayout.setOnClickPendingIntent(R.id.media_play, playPendingIntent)
		customLayout.setViewVisibility(R.id.media_play,
			if (playing) View.GONE else View.VISIBLE
		)

		// PAUSE:
		val pausePendingIntent = PendingIntent.getBroadcast(this, 101,
			Intent(ACTION_MEDIA_PAUSE).apply { setPackage(packageName) },
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		customLayout.setOnClickPendingIntent(R.id.media_pause, pausePendingIntent)
		customLayout.setViewVisibility(R.id.media_pause,
			if (playing) View.VISIBLE else View.GONE
		)

		// PREV:
		val prevPendingIntent = PendingIntent.getBroadcast(this, 102,
			Intent(ACTION_MEDIA_PREV).apply { setPackage(packageName) },
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		customLayout.setOnClickPendingIntent(R.id.media_prev, prevPendingIntent)

		// NEXT:
		val nextPendingIntent = PendingIntent.getBroadcast(this, 103,
			Intent(ACTION_MEDIA_NEXT).apply { setPackage(packageName) },
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		customLayout.setOnClickPendingIntent(R.id.media_next, nextPendingIntent)

		// Elhúzáskor:
		val dismissPendingIntent = PendingIntent.getBroadcast(this, 99,
			Intent(ACTION_NOTIFICATION_DISMISSED).apply { setPackage(packageName) },
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		return NotificationCompat.Builder(this, packageName).run {
			setSmallIcon(R.drawable.ic_launcher_monochrome)
			setCustomContentView(customLayout)
			setDeleteIntent(dismissPendingIntent)
			setOngoing(true)
			setSilent(true)
			build()
		}
	}

	private val notificationDismissReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			updateNotification()
		}
	}

	private fun updateNotification() {
		if (ActivityCompat.checkSelfPermission(this@WebAppPlaybackService, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
		NotificationManagerCompat.from(this@WebAppPlaybackService).notify(1, createNotification())
	}

	@UnstableApi
	override fun onCreate() {
		super.onCreate()

		"MediaService, onCreate".log()

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

		player.addListener(object : Player.Listener {
			override fun onIsPlayingChanged(isPlaying: Boolean) {
				super.onIsPlayingChanged(isPlaying)
				playing = isPlaying
				updateNotification()
			}
		})

		mediaSession = MediaSession.Builder(this, player).run {
			setId("WebAppSession:$packageName")
			build()
		}

		ContextCompat.registerReceiver(this,
			notificationDismissReceiver,
			IntentFilter(ACTION_NOTIFICATION_DISMISSED),
			ContextCompat.RECEIVER_NOT_EXPORTED
		)

		startForeground(1, createNotification())
	}

	override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {}

	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

	override fun onDestroy() {

		try { unregisterReceiver(notificationDismissReceiver) } catch (_: Exception) {}

		mediaSession.run {
			player.stop()
			player.clearMediaItems()
			player.release()
			release()
		}

		try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}

		"MediaService, onDestroy".log()

		super.onDestroy()
	}

	companion object {
		const val ACTION_NOTIFICATION_DISMISSED = "ACTION_0"
		const val ACTION_MEDIA_PAUSE = "ACTION_1"
		const val ACTION_MEDIA_PLAY = "ACTION_2"
		const val ACTION_MEDIA_PREV = "ACTION_3"
		const val ACTION_MEDIA_NEXT = "ACTION_4"
	}
}






private var defaultAPI = """
(function(wnd, doc, edgeToEdge = false) {

	if (wnd.newWebView) return;
	wnd.newWebView = 1;

	Object.defineProperty(Object.prototype, 'params', {
		value: function(obj) {
			if (obj && typeof obj === 'object') {
				for (const key in obj) {
					if (Object.prototype.hasOwnProperty.call(obj, key)) {
						this[key] = obj[key];
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
	});

	const
		obj = { topEnabled:true, btmEnabled:true, topHeight:50, btmHeight:100, topBlur:true, btmBlur:true },
		meta = doc.createElement('meta').params({
			name: 'viewport',
			content: 'width=device-width, initial-scale=1.0, user-scalable=no'
		});

	doc.head.appendChild(meta);

	if (edgeToEdge) {

		let cssText = (
			'body {' +
				(obj.topEnabled ? ('padding-top: ' + obj.topHeight + 'px;') : '') +
				(obj.btmEnabled ? ('padding-bottom: ' + obj.btmHeight + 'px;') : '') +
			'} body::before {' +
				'position: fixed;' +
				'content: "";' +
				'inset: 0;' +
				'pointer-events: none;' +
				'backdrop-filter: blur(10px);' +
				'z-index: 10000;' +
				'mask-image: linear-gradient(to bottom,');

		if (obj.topEnabled && obj.topBlur) cssText += ('black ' + (obj.topHeight * 0.8) + 'px, transparent ' + obj.topHeight + 'px');

		if (obj.topEnabled && obj.topBlur && obj.btmEnabled && obj.btmBlur) cssText += ',';

		if (obj.btmEnabled && obj.btmBlur) cssText += ('transparent calc(100% - ' + obj.btmHeight + 'px), black calc(100% - ' + (obj.btmHeight * 0.6) + 'px)');

		cssText += ')}';

		if (obj.topEnabled || obj.btmEnabled) {
			const style = doc.createElement('style').params({
				textContent: cssText
			});
			doc.head.appendChild(style);
		}

		wnd.addEventListener('touchmove', (e) => {
			if (e.touches[0].clientY > (wnd.screen.height * 0.93)) e.preventDefault();
		}, { passive: false });

		wnd.statusBarHeight = obj.topHeight;
		wnd.navigationBarHeight = obj.btmHeight;
	}

	if (typeof onAndroid === 'function') onAndroid();

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
        android:textSize="28sp"
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
