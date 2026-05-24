package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.api.Content
import com.example.api.GenerateContentRequest
import com.example.api.InlineData
import com.example.api.Part
import com.example.api.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        savedStateRegistryController.performRestore(null)
    }

    fun start() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry
}

data class Message(
    val sender: String, // "user" or "ai"
    val text: String
)

class OverlayService : Service() {

    companion object {
        private var projectionResultCode: Int = 0
        private var projectionResultData: Intent? = null
        private var isProjectionAuthorized = false

        var serviceInstance: OverlayService? = null
            private set

        fun setProjectionResult(resultCode: Int, data: Intent?) {
            projectionResultCode = resultCode
            projectionResultData = data
            isProjectionAuthorized = resultCode == -1 && data != null
        }

        fun hasProjectionPermission(): Boolean = isProjectionAuthorized
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: ComposeView
    private lateinit var overlayView: ComposeView

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var overlayParams: WindowManager.LayoutParams

    private val lifecycleOwner = ServiceLifecycleOwner()

    // Global states in service
    private val chatHistory = mutableStateListOf<Message>()
    private var isExpanded = mutableStateOf(false)
    private var isRecording = mutableStateOf(false)
    private var isProcessing = mutableStateOf(false)
    private var voiceFeedbackEnabled = mutableStateOf(true)

    // Speech Tools
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ProjectionPermissionActivity.ACTION_PROJECTION_AUTHORIZED) {
                // Succeeded authorized screen capture
                addLogMessage("ai", "تم تفعيل إذن تصوير الشاشة بنجاح! يمكنني الآن رؤية وتحليل ما تفعله فوراً. 👁️")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        lifecycleOwner.start()

        // Register action receiver for projection
        registerReceiver(broadcastReceiver, IntentFilter(ProjectionPermissionActivity.ACTION_PROJECTION_AUTHORIZED))

        // Start Foreground Service Notification
        startForeground(1001, createNotification())

        // Initialize TTS
        initTTS()

        // Setup views
        setupBubbleView()
        setupOverlayView()

        // Add intro message
        chatHistory.add(Message("ai", "أهلاً بك! أنا بصيرة، مساعدك الذكي في الخلفية. ✨\nيمكنني الاستماع لصوتك، قراءة شاشتك، وحل المشكلات في أي تطبيق (مثل تيرمكس أو متصفح الملفات). تحدث معي أو انقر على 'تحليل الشاشة' لمساعدتك."))
    }

    private fun initTTS() {
        try {
            textToSpeech = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = textToSpeech?.setLanguage(Locale("ar"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        textToSpeech?.setLanguage(Locale.US) // Fallback
                    }
                    isTtsReady = true
                }
            }
        } catch (e: Exception) {
            isTtsReady = false
        }
    }

    private fun speakOut(text: String) {
        if (voiceFeedbackEnabled.value && isTtsReady && textToSpeech != null) {
            // Clean markdown syntax or emojis to make tts sound smoother
            val cleanText = text.replace(Regex("[*#_`~]"), "")
            textToSpeech?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "BaseeraSpeak")
        }
    }

    private fun startSpeechInput() {
        if (isProcessing.value) return
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن...")
        }

        Handler(Looper.getMainLooper()).post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                }
                
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isRecording.value = true
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isRecording.value = false
                    }
                    override fun onError(error: Int) {
                        isRecording.value = false
                        val errorMsg = when(error) {
                            SpeechRecognizer.ERROR_AUDIO -> "خطأ تسجيل الصوت"
                            SpeechRecognizer.ERROR_CLIENT -> "خطأ في التطبيق"
                            SpeechRecognizer.ERROR_NETWORK -> "فشل في الاتصال بالشبكة"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "انتهت مهلة الشبكة"
                            SpeechRecognizer.ERROR_NO_MATCH -> "لم أستطع تمييز صوتك"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "محرك الصوت مشغول"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "لم يتم الكشف عن كلام"
                            else -> "خلل صامت"
                        }
                        addLogMessage("ai", "⚠️ لم أستطع الاستماع: $errorMsg. يمكنك المحاولة مجدداً أو الكتابة.")
                    }
                    override fun onResults(results: Bundle?) {
                        isRecording.value = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val spokenText = matches[0]
                            addLogMessage("user", spokenText)
                            processUserPrompt(spokenText)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                isRecording.value = false
                addLogMessage("ai", "⚠️ يبدو أن ميزة الصوت غير مدعومة على هذا النظام. يرجى توفير إذن الميكروفون أو الكتابة نصياً.")
            }
        }
    }

    private fun stopSpeechInput() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {}
        isRecording.value = false
    }

    private fun setupBubbleView() {
        bubbleView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                BubbleContent(
                    isExpanded = isExpanded.value,
                    isRecording = isRecording.value,
                    isProcessing = isProcessing.value,
                    onDrag = { dx, dy ->
                        bubbleParams.x += dx.toInt()
                        bubbleParams.y += dy.toInt()
                        try {
                            windowManager.updateViewLayout(bubbleView, bubbleParams)
                        } catch (e: Exception) {}
                    },
                    onClick = {
                        toggleExpandedState()
                    }
                )
            }
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 800
            y = 300
        }

        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun setupOverlayView() {
        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                OverlayContent(
                    messages = chatHistory,
                    isRecording = isRecording.value,
                    isProcessing = isProcessing.value,
                    voiceEnabled = voiceFeedbackEnabled.value,
                    onToggleVoice = { voiceFeedbackEnabled.value = it },
                    onClose = { toggleExpandedState() },
                    onSendText = { text ->
                        addLogMessage("user", text)
                        processUserPrompt(text)
                    },
                    onTriggerVoice = {
                        if (isRecording.value) {
                            stopSpeechInput()
                        } else {
                            startSpeechInput()
                        }
                    },
                    onTriggerScreenCapture = {
                        requestCaptureAndProcess()
                    },
                    onShutdown = {
                        stopSelf()
                    }
                )
            }
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Full width interactive overlay, dismiss keyboard cleanly
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            750, // Height in px (or WRAP_CONTENT up to max size in design layout)
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            windowAnimations = android.R.style.Animation_InputMethod
        }
    }

    private fun toggleExpandedState() {
        isExpanded.value = !isExpanded.value
        if (isExpanded.value) {
            // Expand dialogue bar, focusable to write text
            overlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            try {
                windowManager.addView(overlayView, overlayParams)
            } catch (e: Exception) {}
        } else {
            // Hide overlay dialogue
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {}
            stopSpeechInput()
        }
    }

    private fun addLogMessage(sender: String, text: String) {
        Handler(Looper.getMainLooper()).post {
            chatHistory.add(Message(sender, text))
            if (chatHistory.size > 50) {
                chatHistory.removeAt(0)
            }
        }
    }

    private fun processUserPrompt(text: String) {
        if (isProcessing.value || text.trim().isEmpty()) return
        isProcessing.value = true

        val apiKey = getEffectiveApiKey(this)
        val systemPrompt = "أنت 'بصيرة'، مساعد ذكاء اصطناعي فوري وعملي يرافق المستخدم فوق التطبيقات في نظام أندرويد. " +
                "مهمتك هي مساعدته مباشرة عند الوقوع في أخطاء أو طلب مشورة في أي تطبيق كـ Termux أو متصفحات الملفات. " +
                "إرشاداتك يجب أن تكون شديدة الدقة، مكتوبة بلغة عربية سلسلة ومبسطة، وخطوة بخطوة تفادياً لتشتيت المستخدم. " +
                "إذا طلب منك التوقف، تمنى له يوماً سعيا واشرح له أنه يمكنه إيقافك من خيار الإغلاق."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Compile conversation history
                val parts = mutableListOf<Part>()
                
                // Add context from last 6 messages
                val lastMessages = chatHistory.takeLast(6)
                var convoContextText = ""
                for (msg in lastMessages) {
                    val roleName = if (msg.sender == "user") "المستخدم" else "أنت (بصيرة)"
                    convoContextText += "$roleName: ${msg.text}\n"
                }
                
                convoContextText += "المستخدم وجه إليك الطلب الحالي: $text\nأجب عنه مباشرة بتبسيط وخطوات عملية."
                parts.add(Part(text = convoContextText))

                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = parts)),
                    systemInstruction = Content(parts = listOf(Part(text = systemPrompt)))
                )

                val response = RetrofitClient.service.generateContent(apiKey, request)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                    ?: "عذراً، لم أستطع فهم رسالتك بوضوح."

                withContext(Dispatchers.Main) {
                    addLogMessage("ai", responseText)
                    speakOut(responseText)
                    isProcessing.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLogMessage("ai", "⚠️ فشل في إرسال طلبك: ${e.message ?: "خلل غير معروف"}")
                    isProcessing.value = false
                }
            }
        }
    }

    private fun requestCaptureAndProcess() {
        if (isProcessing.value) return
        isProcessing.value = true

        val apiKey = getEffectiveApiKey(this)
        
        // Ensure we minimize our overlay so it doesn't block the screen capture!
        // Move overlay params to thin or invisible, wait, or simply render invisible for 150ms during capture!
        val systemPrompt = "أنت 'بصيرة'، خبير أندرويد ومستشار الذكاء الاصطناعي الفوري. " +
                "تم منحك لقطة شاشة حالية لما ينظر إليه المستخدم الآن (قد تشتمل على كود تيرمكس، خطأ في ملفات، إلخ). " +
                "حلل الصورة بدقة متناهية واعرف أين المشكلة ثم قدم له إيجازاً وتوجيهاً فورياً وعملياً باللغة العربية لكيفية إصلاح الخلل."

        // Minimize chat overlay momentarily to draw crystal active background
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {}

        // Small delay to ensure view is fully drawn away
        Handler(Looper.getMainLooper()).postDelayed({
            captureScreenAndAnalyze(
                apiKey = apiKey,
                systemInstruction = systemPrompt,
                userPrompt = "يرجى مسح شاشتي الحالية بعين الخبير، وإرشادي باللغة العربية عما يجب علي القيام به لحل هذه العقبة أو لإتمام العملية العالقة.",
                onResponse = { responseText ->
                    // Re-add overlay view
                    try {
                        windowManager.addView(overlayView, overlayParams)
                    } catch (e: Exception) {}
                    addLogMessage("ai", responseText)
                    speakOut(responseText)
                    isProcessing.value = false
                },
                onError = { errorText ->
                    try {
                        windowManager.addView(overlayView, overlayParams)
                    } catch (e: Exception) {}
                    addLogMessage("ai", "⚠️ $errorText")
                    isProcessing.value = false
                }
            )
        }, 200)
    }

    private fun captureScreenAndAnalyze(
        apiKey: String,
        systemInstruction: String,
        userPrompt: String,
        onResponse: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val code = projectionResultCode
        val data = projectionResultData

        if (code != -1 || data == null) {
            // Need projection permission activity
            val intent = ProjectionPermissionActivity.getStartIntent(this)
            startActivity(intent)
            onError("يرجى إعطاء إذن تصوير الشاشة من النافذة المنبثقة ثم أعد النقر على زر تحليل الشاشة.")
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = try {
            projectionManager.getMediaProjection(code, data)
        } catch (e: Exception) {
            onError("فشل في تحضير محرك تصوير الشاشة: ${e.message}")
            return
        }

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        val virtualDisplay = try {
            mediaProjection!!.createVirtualDisplay(
                "BaseeraStream",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )
        } catch (e: Exception) {
            mediaProjection?.stop()
            onError("تعذر بث محتويات الشاشة داخلياً: ${e.message}")
            return
        }

        imageReader.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image == null) {
                virtualDisplay?.release()
                mediaProjection?.stop()
                onError("بث الشاشة فارغ، يرجى المحاولة مرة أخرى.")
                return@setOnImageAvailableListener
            }

            reader.setOnImageAvailableListener(null, null)

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val rawBitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(buffer)
            image.close()

            virtualDisplay?.release()
            mediaProjection?.stop()

            // Crop image to standard screen size
            val cleanBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)

            // Compress & Call multimodal Gemini Flash
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val base64Image = cleanBitmap.toBase64()
                    val requestBody = GenerateContentRequest(
                        contents = listOf(
                            Content(
                                parts = listOf(
                                    Part(text = userPrompt),
                                    Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                                )
                            )
                        ),
                        systemInstruction = Content(parts = listOf(Part(text = systemInstruction)))
                    )

                    val response = RetrofitClient.service.generateContent(apiKey, requestBody)
                    val reply = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        ?: "تم مسح الشاشة لكن لم أقدر على تلخيص محتواها."

                    withContext(Dispatchers.Main) {
                        onResponse(reply)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        onError("فشل الاتصال بـ Gemini: ${e.message}")
                    }
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        // Downscale slightly to improve API upload speed and fit prompt constraints smoothly
        val scaledWidth = (width * 0.7).toInt()
        val scaledHeight = (height * 0.7).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun createNotification(): Notification {
        val channelId = "baseera_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "مساعد بصيرة الفوري في الخلفية",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("بصيرة يعمل في الخلفية")
            .setContentText("الفقاعة العائمة نشطة وجاهزة لمساعدتك فوراً بالصوت والنظر")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun getEffectiveApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("baseera_prefs", Context.MODE_PRIVATE)
        val customKey = prefs.getString("api_key", "") ?: ""
        return if (customKey.isNotEmpty()) customKey else BuildConfig.GEMINI_API_KEY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
        lifecycleOwner.stop()
        unregisterReceiver(broadcastReceiver)

        try {
            windowManager.removeView(bubbleView)
        } catch (e: Exception) {}

        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {}

        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {}

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// Float Bubble Compose View helper
@Composable
fun BubbleContent(
    isExpanded: Boolean,
    isRecording: Boolean,
    isProcessing: Boolean,
    onDrag: (Float, Float) -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .clip(CircleShape)
            .background(
                if (isRecording) Color(0xFFE53935)
                else if (isProcessing) Color(0xFF00E676)
                else Color(0xFF1E293B)
            )
            .border(
                2.dp,
                if (isExpanded) Color(0xFF00E676) else Color.White.copy(alpha = 0.5f),
                CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(50.dp),
                strokeWidth = 3.dp
            )
            Text("🎙️", fontSize = 24.sp)
        } else if (isProcessing) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(50.dp),
                strokeWidth = 3.dp
            )
            Text("✨", fontSize = 24.sp)
        } else {
            Text("👁️‍🗨️", fontSize = 26.sp)
        }
    }
}

// Dialogue Sheet Panel overlay Compose view helper (drawn over apps)
@Composable
fun OverlayContent(
    messages: List<Message>,
    isRecording: Boolean,
    isProcessing: Boolean,
    voiceEnabled: Boolean,
    onToggleVoice: (Boolean) -> Unit,
    onClose: () -> Unit,
    onSendText: (String) -> Unit,
    onTriggerVoice: () -> Unit,
    onTriggerScreenCapture: () -> Unit,
    onShutdown: () -> Unit
) {
    val listState = rememberLazyListState()
    var textInput by remember { mutableStateOf("") }

    // Scroll automatically when new messages are added
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFAF0F172).copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "بصيرة مساعد الذكاء الاصطناعي",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Voice Outloud Switch
                    Text(
                        text = if (voiceEnabled) "🔊 نطق الإجابة" else "🔇 صامت",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable { onToggleVoice(!voiceEnabled) }
                            .padding(horizontal = 8.dp)
                    )

                    // Collapse button
                    Text(
                        text = "➖ تصغير",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onClose() }
                            .padding(horizontal = 8.dp)
                    )

                    // Exit button
                    Text(
                        text = "❌ إيقاف",
                        color = Color(0xFFFF5252),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onShutdown() }
                            .padding(start = 8.dp)
                    )
                }
            }

            Divider(color = Color.White.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 8.dp))

            // Body message list
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (message.sender == "user") Arrangement.End else Arrangement.Start
                        ) {
                            Card(
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (message.sender == "user") 16.dp else 4.dp,
                                    bottomEnd = if (message.sender == "user") 4.dp else 16.dp
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (message.sender == "user") Color(0xFF0F766E) else Color(0xFF334155)
                                ),
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Text(
                                    text = message.text,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(10.dp),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }

                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "⚡ جاري التفكير والتحضير...",
                            color = Color(0xFF00E676),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Dynamic Action bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Button 1: Live Scanning of behind apps
                Button(
                    onClick = { onTriggerScreenCapture() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("👁️ تحليل الشاشة", fontSize = 11.sp, color = Color.White)
                }

                // Button 2: Speech Dictate trigger
                Button(
                    onClick = { onTriggerVoice() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFDC2626) else Color(0xFF3B82F6)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isRecording) "🛑 استماع..." else "🎙️ صوت", fontSize = 11.sp, color = Color.White)
                }

                // Input Box
                TextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("اكتب سؤالاً أو مشكلتك...", fontSize = 12.sp, color = Color.Gray) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1E293B),
                        unfocusedContainerColor = Color(0xFF1E293B),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (textInput.trim().isNotEmpty()) {
                                onSendText(textInput)
                                textInput = ""
                            }
                        }
                    )
                )

                // Send Button
                IconButton(
                    onClick = {
                        if (textInput.trim().isNotEmpty()) {
                            onSendText(textInput)
                            textInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0D9488))
                ) {
                    Text("↩️", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}
