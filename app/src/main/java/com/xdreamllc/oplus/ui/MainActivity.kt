package com.xdreamllc.oplus.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.R

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        @Suppress("DEPRECATION")
        prefs = try {
            getSharedPreferences(Config.PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        }
        makePrefsWorldReadable()

        setContent {
            LightTheme {
                MainScreen()
            }
        }
    }

    private fun makePrefsWorldReadable() {
        try {
            val prefsDir = java.io.File(applicationInfo.dataDir, "shared_prefs")
            prefsDir.setExecutable(true, false)
            prefsDir.setReadable(true, false)
            val prefsFile = java.io.File(prefsDir, "${Config.PREFS_NAME}.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
            }
        } catch (_: Throwable) {}
    }

    // ========== Data classes ==========

    data class AssistantInfo(
        val installed: Boolean,
        val name: String,
        val packageName: String,
        val icon: Bitmap? = null
    )

    // ========== Helper functions ==========

    private fun getCurrentAssistantInfo(): AssistantInfo? {
        return try {
            val cr = contentResolver
            val assistantStr = Settings.Secure.getString(cr, "assistant")
            if (assistantStr.isNullOrEmpty()) return null

            val cn = ComponentName.unflattenFromString(assistantStr) ?: return null
            val pkg = cn.packageName
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val icon = drawableToBitmap(appInfo.loadIcon(pm))
            val name = appInfo.loadLabel(pm).toString()

            AssistantInfo(true, name, pkg, icon)
        } catch (_: Exception) {
            null
        }
    }

    private fun getAppInfo(pkg: String): AssistantInfo {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val icon = drawableToBitmap(appInfo.loadIcon(pm))
            val name = appInfo.loadLabel(pm).toString()
            AssistantInfo(true, name, pkg, icon)
        } catch (_: PackageManager.NameNotFoundException) {
            AssistantInfo(false, pkg, pkg, null)
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = maxOf(drawable.intrinsicWidth, 1)
        val h = maxOf(drawable.intrinsicHeight, 1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }

    private fun openDefaultAssistantSettings() {
        try {
            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                val intent = Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Throwable) {}
        }
    }

    // ========== UI Composables ==========

    @Composable
    fun MainScreen() {
        var powerMode by remember {
            mutableIntStateOf(prefs.getInt(Config.KEY_POWER_MODE, Config.POWER_MODE_GEMINI))
        }
        var gestureBarEnabled by remember {
            mutableStateOf(prefs.getBoolean(Config.KEY_GESTURE_BAR_ENABLED, true))
        }
        val context = LocalContext.current
        var assistantInfo by remember { mutableStateOf(getCurrentAssistantInfo()) }
        
        // Refresh assistantInfo when returning from settings
        val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    assistantInfo = getCurrentAssistantInfo()
                }
            }
            lifecycleOwner?.lifecycle?.addObserver(observer)
            onDispose {
                lifecycleOwner?.lifecycle?.removeObserver(observer)
            }
        }

        val geminiInfo = remember { getAppInfo("com.google.android.apps.bard") }
        val gsaInfo = remember { getAppInfo(Config.PKG_GOOGLE) }

        val scrollState = rememberScrollState()

        Scaffold(
            containerColor = Color.White
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                // Header
                Text(
                    text = "Oplus Assistant Hook",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A)
                )
                Text(
                    text = "ColorOS 助手替换模块 v1.1.0",
                    fontSize = 13.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Module status
                ModuleStatusCard()

                Spacer(modifier = Modifier.height(16.dp))

                // Default assistant check
                DefaultAssistantCard(assistantInfo)

                Spacer(modifier = Modifier.height(24.dp))

                // Section 1: Power button
                SectionHeader(
                    title = "电源键长按替换",
                    subtitle = "拦截 ColorOS 长按电源键唤醒小布助手"
                )

                Spacer(modifier = Modifier.height(10.dp))

                RadioOptionCard(
                    title = "Gemini",
                    subtitle = "启动 Google Gemini 助手",
                    iconResId = R.drawable.gemini,
                    selected = powerMode == Config.POWER_MODE_GEMINI,
                    accentColor = Color(0xFF4285F4),
                    onClick = {
                        powerMode = Config.POWER_MODE_GEMINI
                        prefs.edit().putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_GEMINI).apply()
                        makePrefsWorldReadable()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                RadioOptionCard(
                    title = "一圈即搜",
                    subtitle = "启动 Google Circle to Search",
                    iconResId = R.drawable.google,
                    selected = powerMode == Config.POWER_MODE_CIRCLE,
                    accentColor = Color(0xFF34A853),
                    onClick = {
                        powerMode = Config.POWER_MODE_CIRCLE
                        prefs.edit().putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_CIRCLE).apply()
                        makePrefsWorldReadable()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                RadioOptionCard(
                    title = "不替换",
                    subtitle = "保持原始小布助手行为",
                    iconResId = null,
                    selected = powerMode == Config.POWER_MODE_NONE,
                    accentColor = Color(0xFF999999),
                    onClick = {
                        powerMode = Config.POWER_MODE_NONE
                        prefs.edit().putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_NONE).apply()
                        makePrefsWorldReadable()
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Section 2: Gesture bar
                SectionHeader(
                    title = "手势指示条长按替换",
                    subtitle = "拦截长按导航手势指示条唤醒小布识屏，替换为一圈即搜"
                )

                Spacer(modifier = Modifier.height(10.dp))

                ToggleCard(
                    title = "启用手势指示条替换",
                    subtitle = "替换为 Google 一圈即搜",
                    checked = gestureBarEnabled,
                    onCheckedChange = {
                        gestureBarEnabled = it
                        prefs.edit().putBoolean(Config.KEY_GESTURE_BAR_ENABLED, it).apply()
                        makePrefsWorldReadable()
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                InfoCard(
                    text = "此功能需要在系统设置 > 系统导航方式中启用「长按手势指示条唤醒小布识屏」选项"
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Footer
                HorizontalDivider(color = Color(0xFFEEEEEE))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "修改设置后需重启系统方可生效",
                    fontSize = 12.sp,
                    color = Color(0xFFFF9500),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    @Composable
    fun ModuleStatusCard() {
        val isActive = isModuleActive()
        val statusColor = if (isActive) Color(0xFF34A853) else Color(0xFFEA4335)
        val bgColor = if (isActive) Color(0xFFF0FAF0) else Color(0xFFFFF0EF)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(bgColor)
                .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isActive) "模块已激活" else "模块未激活",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1A1A)
                    )
                    Text(
                        text = if (isActive) "功能正常运行中" else "请在 LSPosed 中启用本模块并重启系统",
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }
            }
        }
    }

    @Composable
    fun DefaultAssistantCard(assistantInfo: AssistantInfo?) {
        val isGSA = assistantInfo?.packageName == Config.PKG_GOOGLE
        val bgColor = if (isGSA) Color(0xFFF0FAF0) else Color(0xFFFFF8E1)
        val borderColor = if (isGSA) Color(0xFF34A853).copy(alpha = 0.3f) else Color(0xFFFFA000).copy(alpha = 0.3f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                .clickable { openDefaultAssistantSettings() }
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon
                if (assistantInfo?.icon != null) {
                    Image(
                        bitmap = assistantInfo.icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFA000),
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "默认数字助理",
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                    Text(
                        text = assistantInfo?.name ?: "未设置",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A1A)
                    )
                    if (!isGSA) {
                        Text(
                            text = "建议设置为 Google 以确保功能正常",
                            fontSize = 11.sp,
                            color = Color(0xFFFFA000)
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "打开设置",
                    tint = Color(0xFFBBBBBB),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    @Composable
    fun SectionHeader(title: String, subtitle: String) {
        Column {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A)
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Color(0xFF888888),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }

    @Composable
    fun RadioOptionCard(
        title: String,
        subtitle: String,
        iconResId: Int?,
        selected: Boolean,
        accentColor: Color,
        onClick: () -> Unit
    ) {
        val borderColor by animateColorAsState(
            targetValue = if (selected) accentColor else Color(0xFFE0E0E0),
            animationSpec = tween(250), label = "border"
        )
        val bgColor by animateColorAsState(
            targetValue = if (selected) accentColor.copy(alpha = 0.06f) else Color(0xFFFAFAFA),
            animationSpec = tween(250), label = "bg"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Icon or radio indicator
                if (iconResId != null) {
                    Image(
                        painter = painterResource(id = iconResId),
                        contentDescription = title,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFEEEEEE)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("X", fontSize = 14.sp, color = Color(0xFF999999), fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A1A)
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }

                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .border(2.dp, Color(0xFFCCCCCC), CircleShape)
                    )
                }
            }
        }
    }

    @Composable
    fun ToggleCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val borderColor by animateColorAsState(
            targetValue = if (checked) Color(0xFF34A853) else Color(0xFFE0E0E0),
            animationSpec = tween(250), label = "toggleBorder"
        )
        val bgColor by animateColorAsState(
            targetValue = if (checked) Color(0xFFF0FAF0) else Color(0xFFFAFAFA),
            animationSpec = tween(250), label = "toggleBg"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.google),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A1A)
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }

                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF34A853),
                        uncheckedThumbColor = Color(0xFFBBBBBB),
                        uncheckedTrackColor = Color(0xFFE0E0E0)
                    )
                )
            }
        }
    }

    @Composable
    fun InfoCard(text: String) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF5F5F5))
                .padding(14.dp)
        ) {
            Row {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color(0xFFFF9500),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    fontSize = 12.sp,
                    color = Color(0xFF666666),
                    lineHeight = 18.sp
                )
            }
        }
    }

    @androidx.annotation.Keep
    fun isModuleActive(): Boolean {
        return false
    }
}

@Composable
fun LightTheme(content: @Composable () -> Unit) {
    val lightColors = lightColorScheme(
        primary = Color(0xFF4285F4),
        onPrimary = Color.White,
        surface = Color.White,
        onSurface = Color(0xFF1A1A1A),
        background = Color.White,
        onBackground = Color(0xFF1A1A1A)
    )

    MaterialTheme(
        colorScheme = lightColors,
        content = content
    )
}
