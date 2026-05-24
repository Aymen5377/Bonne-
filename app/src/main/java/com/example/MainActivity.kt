package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val hasOverlayState = mutableStateOf(false)
    private val hasMicState = mutableStateOf(false)
    private val hasNotificationState = mutableStateOf(false)
    private val hasProjectionState = mutableStateOf(false)
    private val isServiceRunningState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0F172A) // Deep cosmic navy background
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        hasOverlay = hasOverlayState.value,
                        hasMic = hasMicState.value,
                        hasNotification = hasNotificationState.value,
                        hasProjection = hasProjectionState.value,
                        isServiceRunning = isServiceRunningState.value,
                        onRequestOverlay = { requestOverlayPermission() },
                        onRequestMic = { requestMicPermission() },
                        onRequestNotification = { requestNotificationPermission() },
                        onRequestProjection = { requestProjectionPermission() },
                        onStartService = { startOverlayService() },
                        onStopService = { stopOverlayService() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        hasOverlayState.value = Settings.canDrawOverlays(this)
        hasMicState.value = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasNotificationState.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        hasProjectionState.value = OverlayService.hasProjectionPermission()
        isServiceRunningState.value = OverlayService.serviceInstance != null
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "يرجى تحديد تفعيل الظهور فوق التطبيقات للمتابعة", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            201
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                202
            )
        }
    }

    private fun requestProjectionPermission() {
        // Launches the transparent popup activity which automatically triggers MediaProjection consent dialog
        val intent = ProjectionPermissionActivity.getStartIntent(this)
        startActivity(intent)
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "يرجى إعطاء صلاحية الظهور فوق التطبيقات أولاً", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updatePermissionStates()
        Toast.makeText(this, "تم إطلاق المساعد بنجاح! راقب الفقاعة العائمة على شاشتك.", Toast.LENGTH_LONG).show()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
        // Reset projection token static fields to match state logic
        OverlayService.setProjectionResult(0, null)
        updatePermissionStates()
        Toast.makeText(this, "تم إيقاف المساعد وفصل فقاعة الشاشة.", Toast.LENGTH_SHORT).show()
    }

    // Handles request permissions response callback directly in activity
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStates()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    hasOverlay: Boolean,
    hasMic: Boolean,
    hasNotification: Boolean,
    hasProjection: Boolean,
    isServiceRunning: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestProjection: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Key Save Controller
    val prefs = remember { context.getSharedPreferences("baseera_prefs", Context.MODE_PRIVATE) }
    var keyInput by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Cosmic Animated Glow Top Header
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF0369A1), Color(0xFF0D9488))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("👁️‍💫", fontSize = 42.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "بصيرة AI",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "المساعد الذكي المتنقل فوق التطبيقات",
            color = Color(0xFF38BDF8),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(18.dp))

        // 1. Storage API Key Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "🔑 مفتاح واجهة برمجة تطبيقات Gemini",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "أدخل رمز التوكن الخاص بك ليتم استخدامه للذكاء الاصطناعي، وإلا سيقوم بالتمرير على المفتاح الأساسي الافتراضي للنظام.",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )

                TextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text("أدخل هنا رمز التوكن API Key...", color = Color.Gray, fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color(0xFF0D9488)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        prefs.edit().putString("api_key", keyInput.trim()).apply()
                        Toast.makeText(context, "تم حفظ توكن Gemini بنجاح!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
                ) {
                    Text("حفظ المفتاح", fontSize = 12.sp, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Clear Status Control Panel for Service
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🎛️ لوحة تحكم تشغيل المساعد",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isServiceRunning) Color(0xFF0F766E).copy(alpha = 0.3f)
                            else Color(0xFF475569).copy(alpha = 0.2f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (isServiceRunning) Color(0xFF0E7490) else Color(0xFF475569),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isServiceRunning) "نشط ومفعل" else "متوقف",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "حالة الفقاعة العائمة في النظام:",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onStopService,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(12.dp),
                        enabled = isServiceRunning
                    ) {
                        Text("إيقاف المساعد", color = Color.White, fontSize = 13.sp)
                    }

                    Button(
                        onClick = onStartService,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isServiceRunning
                    ) {
                        Text("إطلاق المساعد", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Permissions Checklist Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "⚙️ صلاحيات الترخيص المطلوبة",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "يرجى توفير الصلاحيات التالية لتشغيل جميع الميزات بنجاح (رؤية الشاشة والتحاور الصوتي).",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Item 1: Overlay
                PermissionRow(
                    title = "الظهور فوق التطبيقات (الOverlay)",
                    description = "لعرض الفقاعة العائمة ولوحة التحاور الفوقية.",
                    isGranted = hasOverlay,
                    onGrantClick = onRequestOverlay
                )

                Divider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 10.dp))

                // Item 2: Microphone
                PermissionRow(
                    title = "تسجيل الصوت (الميكروفون)",
                    description = "للقدرة على التكلم والدردشة بالصوت مع بصيرة.",
                    isGranted = hasMic,
                    onGrantClick = onRequestMic
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Divider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 10.dp))
                    // Item 3: Post notifications
                    PermissionRow(
                        title = "الإشعارات المستمرة (Notifications)",
                        description = "للحفاظ على استجابة المساعد وبقائه في الخلفية بنشاط.",
                        isGranted = hasNotification,
                        onGrantClick = onRequestNotification
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 10.dp))

                // Item 4: Screen recording authorization
                PermissionRow(
                    title = "ترخيص تصوير الشاشة (Screen Stream)",
                    description = "لمسح الخطأ في تيرمكس والملفات وتحليله بنظرة الخبير.",
                    isGranted = hasProjection,
                    onGrantClick = onRequestProjection
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Manual / User Guide Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "📖 دليل الاستخدام السريع",
                    color = Color(0xFF38BDF8),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )

                Spacer(modifier = Modifier.height(10.dp))

                val guideItems = listOf(
                    "1️⃣ املأ رمز التوكن API Key الخاص بك أو اعتمد على المفتاح المدمج للبلدوم.",
                    "2️⃣ قم بتفعيل الصلاحيات الأربعة المذكورة أعلاه لراحة كاملة وميزات فائقة.",
                    "3️⃣ انقر على 'إطلاق المساعد' لتظهر لك فقاعة بصيرة الخفيفة على الشاشة.",
                    "4️⃣ انتقل لأي تطبيق تريده (مثل تطبيق Termux أو مدير الملفات لتجربته).",
                    "5️⃣ انقر على فقاعة بصيرة لتفتح لك لوحة التحاور. من هناك يمكنك الكتابة نصياً، أو النقر على 'صوت' لتتحدث بلسانك وجعل بصيرة يحلل خطأك ويقترح الأوامر مباشرة، أو النقر على 'تحليل الشاشة' ليقوم بتصوير شاشتك ورؤية الخطأ والعمل البرمجي لحله.",
                    "6️⃣ للتوقف: انقر على خيار 'إيقاف' الموجود أعلى لوحة المساعد، أو قل له صراحة بصوتك 'توقف' وسيتمنى لك يوماً سعيداً ويريحك."
                )

                guideItems.forEach { item ->
                    Text(
                        text = item,
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Right,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Copyright info
        Text(
            text = "صنع بكل حب وبصيرة لراحتك على أندرويد © 2026",
            color = Color.DarkGray,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { if (!isGranted) onGrantClick() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) Color(0xFF0F766E) else Color(0xFF2563EB)
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier
                .height(28.dp)
                .width(76.dp)
        ) {
            Text(
                text = if (isGranted) "مفعّل ✓" else "تفعيل ⚡",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = description,
                color = Color.Gray,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
