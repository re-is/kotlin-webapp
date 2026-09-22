@file:Suppress("unused")

package com.example.absolute

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
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
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
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
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt


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


fun networkGateWay(): String {
	val address = NetworkInterface.getNetworkInterfaces()
		?.asSequence()
		?.filter { it.name.contains("lan") }
		?.flatMap { it.inetAddresses.asSequence() }
		?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }

	val ip = address?.hostAddress ?: return ""
	return "http://${ip.substringBeforeLast(".")}.254"
}


fun isSystemLightMode(context: Context): Boolean {
	return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
}


fun Window.fullScreen(mode: Boolean) {
	val controller = WindowInsetsControllerCompat(this, this.decorView)
	if (mode) {
		controller.hide(WindowInsetsCompat.Type.systemBars())
		controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
	} else {
		controller.show(WindowInsetsCompat.Type.systemBars())
		controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
	}
}


fun webResourceRequestBlock(): WebResourceResponse = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))


//-------------------------------------------------------------------------->


fun WebView.evalJs(js: String, callback: ((String?) -> Unit)? = null) {
	this.post {
		this.evaluateJavascript(js) { result ->
			callback?.invoke(result)
		}
	}
}


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
 *	<!-- TYPE_MEDIA -->
 *	<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
 *	<uses-permission android:name="android.permission.WAKE_LOCK" />
 *	<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *	<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
 *	<uses-permission android:name="android.permission.BLUETOOTH" />
 *	<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

Service, TYPE_MEDIA

 *	<application>
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

	private lateinit var bluetoothEventCallback: ((String) -> Unit)
	private lateinit var params: WindowManager.LayoutParams
	private val powerManager = activity.getSystemService(POWER_SERVICE) as PowerManager

	private val bluetoothReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			when (intent?.action) {
				BluetoothDevice.ACTION_ACL_CONNECTED,
				BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
					checkCurrentBluetoothState()
				}
			}
		}
	}

	private fun checkCurrentBluetoothState() {
		val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
		val adapter = bluetoothManager?.adapter

		if (adapter != null && adapter.isEnabled) {
			try {
				if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
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

	private val bodyFunction = """
		(function() {

			if (window.newWebView) return;
			window.newWebView = 1;

			const
				style = document.createElement('style'),
				meta = document.createElement('meta'),
				obj = { topEnabled:true, btmEnabled:true, topHeight:50, btmHeight:100, topBlur:true, btmBlur:true };

			meta.name = 'viewport';
			meta.content = 'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=1.0, user-scalable=no';

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

			style.textContent = cssText;
			document.head.appendChild(meta);
			if (obj.topEnabled && obj.btmEnabled) document.head.appendChild(style);

			window.addEventListener('touchstart', (e) => {
				if (e.touches[0].clientY > (screen.height * 0.94)) e.preventDefault();
			}, { passive: false });

		})();
	""".trimIndent()

	// Global ---------------------------------->
	companion object {
		const val TYPE_NORMAL = 0
		const val TYPE_MEDIA = 1
		const val STYLE_NORMAL = 0
		const val STYLE_EDGE_TO_EDGE = 1
		const val STYLE_FULL_SCREEN = 2
		const val ORIENTATION_AUTO = 0
		const val ORIENTATION_FIXED_PORTRAIT = 1
		const val ORIENTATION_FIXED_LANDSCAPE = 2
	}

	// Protected ------------------------------->
	lateinit var innerWebView: WebView
		private set
	var dpiScale = 1f
		private set

	// Instance -------------------------------->
	class StatusBar {
		var enabled = true
		var blur = true
		var height = "0"
	}

	class NavigationBar {
		var enabled = true
		var blur = true
		var height = "0"
	}

	val statusBar = StatusBar()
	val navigationBar = NavigationBar()
	var ovarlayPermissionAllowed = false
	var windowOrientation: Int = ORIENTATION_AUTO
		set(value) {
			field = value
			activity.requestedOrientation = when (value) {
				ORIENTATION_FIXED_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
				ORIENTATION_FIXED_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
				else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
			}
		}

	fun ktTouch(ktLeft: Int, ktTop: Int) {
		innerWebView.touch(ktLeft, ktTop)
	}

	fun jsTouch(jsLeft: Int, jsTop: Int) {
		val left = (jsLeft * dpiScale).toInt()
		val top = (jsTop * dpiScale).toInt()
		innerWebView.touch(left, top)
	}

	fun bluetoothListener(callback: (name: String) -> Unit): WebApp {
		bluetoothEventCallback = callback
		checkCurrentBluetoothState()
		return this
	}



	@SuppressLint(
		"SetJavaScriptEnabled",
		"JavascriptInterface",
		"SourceLockedOrientationActivity",
		"ClickableViewAccessibility"
	)
	fun build(): WebApp {

		// Törli a Destroy/ killProcess-t !
		MAIN_LOOPER.removeCallbacksAndMessages(null)

		// Nagyméretű ablaknál:
		if (windowStyle > STYLE_NORMAL) {
			if (windowStyle == STYLE_FULL_SCREEN) activity.window.fullScreen(true)
			else {
				val s = SystemBarStyle.dark(Color.TRANSPARENT)
				activity.enableEdgeToEdge(statusBarStyle = s, navigationBarStyle = s)
			}
		}

		// WebView beállítása:
		innerWebView = WebView(activity).apply {
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
				useWideViewPort = true
			}
			webChromeClient = object : WebChromeClient() {
				override fun getDefaultVideoPoster(): Bitmap {
					return createBitmap(1,1)
				}
			}
			setBackgroundColor(Color.TRANSPARENT)
			addJavascriptInterface(activity, "android")

			if (windowStyle == STYLE_EDGE_TO_EDGE) setOnTouchListener { _, event ->
				if (event.action == MotionEvent.ACTION_UP) edgeToEdgeBarColors()
				false
			}
		}

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
		activity.registerReceiver(bluetoothReceiver, IntentFilter().apply {
			addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
			addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
		})

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
			params = WindowManager.LayoutParams().apply {
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
					width = activity.windowManager.currentWindowMetrics.bounds.width()
					height = activity.windowManager.currentWindowMetrics.bounds.height()
				}
			}

			// WebView hozzáadás:
			activity.windowManager.addView(innerWebView, params)

			// Meghívás, hogy akkor is legyen ha én nem használom. Később felülíródik:
			media.listener {}

			// MediaController beállítása:
			val sessionToken = SessionToken(activity, ComponentName(activity, WebAppPlaybackService::class.java))
			controllerFuture = MediaController.Builder(activity, sessionToken).buildAsync()
			controllerFuture.addListener({
				media.controller = controllerFuture.get()
				controllerListener = controllerListenerRegister()
				media.controller.addListener(controllerListener!!)
				controllerReadyCallback.invoke()
			}, ContextCompat.getMainExecutor(activity))
		}

		// Folytatás csak ebben a stílusban:
		if (windowStyle != STYLE_EDGE_TO_EDGE) return this



		// Rendszersávok méretei:
		ViewCompat.setOnApplyWindowInsetsListener(innerWebView) { _, insets ->
			// Ha van magasság:
			if (innerWebView.height > 0) {
				val statusHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top.toFloat()
				val navigHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom.toFloat()

				if (statusBar.height == "0")
					statusBar.height = "parseInt(screen.height / ${(innerWebView.height / statusHeight)})"

				if (navigationBar.height == "0" && navigHeight > 0f)
					navigationBar.height = "parseInt(screen.height / ${(innerWebView.height / navigHeight)})"

				/* Ez csak kísérletezéshez kell:*/
				//bodyFunction = activity.assets.open("barHeights.js").bufferedReader().use { it.readText() }

				ViewCompat.setOnApplyWindowInsetsListener(innerWebView, null)
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

			var loaded = false
			override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
				super.onPageStarted(view, url, favicon)
				if (!loaded) return
				loaded = false
				view?.alpha = 0f
				view?.let { wv ->
					onStart?.invoke(wv, url)
					wv.post {
						wv.evaluateJavascript("screen.width") { width ->
							val jsWidth = width?.replace("\"", "")?.toFloat() ?: 1f
							val dpi = activity.windowManager.currentWindowMetrics.bounds.width() / jsWidth
							dpiScale = ((dpi * 100).roundToInt() / 100f)
						}
					}
				}
			}

			override fun onPageFinished(view: WebView?, url: String?) {
				if (loaded) return
				loaded = true
				view?.animate()?.alpha(1f)?.setDuration(600)?.start()
				view?.let { wv ->
					wv.post {
						if (windowStyle == STYLE_EDGE_TO_EDGE) {
							edgeToEdgeBarColors()
							// Felső/Alsó sávok:
							val js = bodyFunction.replace(
								"obj = { topEnabled:true, btmEnabled:true, topHeight:50, btmHeight:100, topBlur:true, btmBlur:true }",
								"obj = { topEnabled:${statusBar.enabled}, btmEnabled:${navigationBar.enabled}, topHeight:${statusBar.height}, btmHeight:${navigationBar.height}, topBlur:${statusBar.blur}, btmBlur:${navigationBar.blur} }"
							)
							wv.evaluateJavascript(js, null)
						}
						// callback
						onLoad?.invoke(wv, url)
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
	 Tartalom lehet:

	 *	""
	 *	"http..."
	 *	"index.html"
	 *	"<html></html>"
	 */
	fun loadContent(content: String): WebApp {
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
		return this
	}



	private var resumedActivity = false
	/**
	 Csak TYPE_MEDIA-hoz kell !

	 *	if (windowType == TYPE_NORMAL) return
	 */
	fun topResumedActivityChanged(isTopResumedActivity: Boolean) {
		if (windowType == TYPE_NORMAL) return
		// onResume:
		if (isTopResumedActivity) {
			// Ha nincs engedély onCreate-kor:
			if (!ovarlayPermissionAllowed) {
				// Ha visszatéréskor van engedély, újranyitás Destroy és Create:
				if (Settings.canDrawOverlays(activity)) {
					MAIN_LOOPER.postDelayed({
						val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.apply {
							addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
						}
						activity.startActivity(intent)
						activity.finishAndRemoveTask()
						Runtime.getRuntime().exit(0)
					}, 300)
				}
				// Ha nincs, akkor csak Destroy:
				else if (resumedActivity) {
					MAIN_LOOPER.postDelayed({
						activity.finishAndRemoveTask()
					}, 300)
				}
				resumedActivity = true
				return
			}
			// Restore:
			params.flags = params.flags and (
					WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
					WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
			).inv()
			activity.windowManager.updateViewLayout(innerWebView, params)
			innerWebView.requestFocus()
			// App megnyitásakor még ne fusson le az onLoad miatt !
			if (resumedActivity) innerWebView.animate()?.alpha(1f)?.setDuration(100)?.startDelay = 300
			resumedActivity = true
			return
		}
		// OnPause:
		if (ovarlayPermissionAllowed && powerManager.isInteractive) {
			params.flags = params.flags or
					WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
					WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

			activity.windowManager.updateViewLayout(innerWebView, params)
			innerWebView.animate()?.alpha(0f)?.setDuration(200)?.startDelay = 0
		}
	}



	fun destroy() {
		if (!ovarlayPermissionAllowed) return

		CookieManager.getInstance().flush()
		activity.unregisterReceiver(bluetoothReceiver)

		MAIN_LOOPER.removeCallbacksAndMessages(null)

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


		// Ablakok törlése:
		if (windowType == TYPE_NORMAL) {
			try { (innerWebView.parent as? ViewGroup)?.removeView(innerWebView) } catch (e: Throwable) {}
		}
		else {

			controllerListener?.let {
				media.controller.removeListener(it)
				controllerListener = null
			}

			media.controller.stop()
			media.controller.release()

			try { MediaController.releaseFuture(controllerFuture) } catch (e: Throwable) {}

			try { activity.windowManager.removeViewImmediate(innerWebView) } catch (e: Throwable) {}
		}


		// WebView törlés:
		try { innerWebView.destroy() } catch (e: Throwable) {}

		// KILL maradék:
		MAIN_LOOPER.postDelayed({ Runtime.getRuntime().exit(0) }, 300)
	}



	//--------------------------------------------------------------------------------->
	//				MEDIA
	//--------------------------------------------------------------------------------->



	private lateinit var controllerEventCallback: ((String) -> Unit)
	private lateinit var controllerReadyCallback: (() -> Unit)
	private var controllerListener: Player.Listener? = null
	private lateinit var controllerFuture: ListenableFuture<MediaController>
	inner class Media {

		// Public !
		lateinit var controller: MediaController

		/**
		 *	event -> PLAY, PAUSE, BACK, NEXT
		 */
		fun listener(callback: (String) -> Unit) {
			controllerEventCallback = callback
		}

		/**
		 *	Wait to build Controller
		 */
		fun waitForController(callback: () -> Unit) {
			controllerReadyCallback = callback
		}

		/**
		 *	title: Zene címe
		 *	background: hexColor, imageURL, default
		 */
		fun setup(title: String, background: String = "") {

			if (windowType == TYPE_NORMAL || !::controller.isInitialized) return

			val parsedColor = (	if (background.startsWith("#")) background.toColorInt()
								else if (isSystemLightMode(activity)) -1	// Fehér
								else 0 )									// Fekete

			val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.RGB_565).apply {
				eraseColor(parsedColor)
			}

			val stream = ByteArrayOutputStream()
			bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
			val artworkBytes = stream.toByteArray()


			val noNameItem = MediaItem.Builder().run {
				setMediaId("noname")
				setUri("asset:///silent.mp3")
				setMediaMetadata(MediaMetadata.Builder().run {
					setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_MEDIA)
					setTitle("...")
					build()
				})
				build()
			}

			val mainItem = MediaItem.Builder().run {
				setMediaId("main")
				setUri("asset:///silent.mp3")
				setMediaMetadata(MediaMetadata.Builder().run {
					// teszt "https://i.ytimg.com/vi/e8_Ddw0H0YA/sddefault.jpg"
					if (background.startsWith("http")) setArtworkUri(background.toUri())
					else setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_MEDIA)
					setTitle(title)
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
	}

	val media = Media()



	private fun controllerListenerRegister(): Player.Listener {
		return object : Player.Listener {

			fun toMain() {
				media.controller.seekTo(1, 0)
				media.controller.pause()
			}

			fun change(e: String) {
				events += e
				MAIN_LOOPER.removeCallbacks(ev)
				MAIN_LOOPER.postDelayed(ev, 200)
			}

			var events = ""
			val ev = Runnable {

				if (events == "PLAY") {
					controllerEventCallback.invoke("PLAY")
				}
				else if (events == "PAUSE") {
					controllerEventCallback.invoke("PAUSE")
				}
				else if (events.contains("BACK")) {
					controllerEventCallback.invoke("BACK")
					toMain()
				}
				else if (events.contains("NEXT")) {
					controllerEventCallback.invoke("NEXT")
					toMain()
				}

				events = ""
			}

			override fun onIsPlayingChanged(isPlaying: Boolean) {
				change(if (isPlaying) "PLAY" else "PAUSE")
			}

			override fun onTracksChanged(tracks: Tracks) {
				if (tracks.groups.isEmpty()) return
				when (media.controller.currentMediaItemIndex) {
					0 -> change("BACK")
					2 -> change("NEXT")
				}
			}
		}
	}
}









class WebAppPlaybackService : MediaSessionService(), MediaSession.Callback {

	private lateinit var mediaSession: MediaSession

	@OptIn(UnstableApi::class)
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

		mediaSession = MediaSession.Builder(this, player).run {
			setCallback(WebAppPlaybackService())
			setId("WebAppSession:$packageName")
			build()
		}
	}

	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

	override fun onDestroy() {

		mediaSession.run {
			player.stop()
			player.clearMediaItems()
			player.release()
			release()
		}

		"MediaService, onDestroy".log()

		super.onDestroy()
	}
}
