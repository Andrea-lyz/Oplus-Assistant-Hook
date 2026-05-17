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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.R
import io.github.libxposed.service.XposedService

class MainActivity : ComponentActivity() {

    /**
     * Settings store. Always wraps the framework's RemotePreferences when the Xposed service is
     * connected, falling back to a private SharedPreferences only as a last resort so the UI is
     * still functional when the framework is not active (the hooks then see defaults, which is
     * what the activation card surfaces to the user).
     */
    private class SettingsStore(private val context: Context) {

        @Volatile
        private var remote: SharedPreferences? = null

        private val fallback: SharedPreferences =
            context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)

        fun bind(service: XposedService?) {
            remote = try {
                service?.getRemotePreferences(Config.PREFS_NAME)
            } catch (_: Throwable) {
                null
            }
            if (remote != null) {
                migrateLocalToRemote()
            }
        }

        private fun current(): SharedPreferences = remote ?: fallback

        fun getInt(key: String, default: Int): Int = current().getInt(key, default)

        fun getBoolean(key: String, default: Boolean): Boolean = current().getBoolean(key, default)

        fun putInt(key: String, value: Int) {
            current().edit().putInt(key, value).apply()
        }

        fun putBoolean(key: String, value: Boolean) {
            current().edit().putBoolean(key, value).apply()
        }

        /**
         * Copies any value already saved to the local SharedPreferences (e.g. configured before the
         * framework finished binding) into the remote store, so the hook side picks them up.
         */
        private fun migrateLocalToRemote() {
            val target = remote ?: return
            val all = fallback.all
            if (all.isEmpty()) return
            val editor = target.edit()
            for ((key, value) in all) {
                when (value) {
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> Unit
                }
            }
            editor.apply()
        }
    }

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settings = SettingsStore(applicationContext)
        settings.bind(App.service)

        setContent {
            LightTheme {
                MainScreen()
            }
        }
    }

    data class AssistantInfo(
        val name: String,
        val packageName: String,
        val icon: Bitmap? = null
    )

    private fun getCurrentAssistantInfo(): AssistantInfo? {
        return try {
            val assistantStr = Settings.Secure.getString(contentResolver, "assistant")
            if (assistantStr.isNullOrEmpty()) return null

            val component = ComponentName.unflattenFromString(assistantStr) ?: return null
            val appInfo = packageManager.getApplicationInfo(component.packageName, 0)
            AssistantInfo(
                name = appInfo.loadLabel(packageManager).toString(),
                packageName = component.packageName,
                icon = drawableToBitmap(appInfo.loadIcon(packageManager))
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val width = maxOf(drawable.intrinsicWidth, 1)
        val height = maxOf(drawable.intrinsicHeight, 1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun openDefaultAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Throwable) {
            try {
                startActivity(Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Best-effort: write Google's VoiceInteractionService as the system default. Requires
     * WRITE_SECURE_SETTINGS, which is normally only granted via adb (`pm grant ... WRITE_SECURE_SETTINGS`)
     * or a system signature. When that grant is missing we fall through to opening the native voice
     * input settings page so the user can pick Google manually.
     *
     * Doing this from the UI process (not from the hook on each press) avoids the racy
     * VoiceInteractionManagerService rebind that caused Gemini to occasionally fail to appear when
     * the system_server route was used.
     */
    private fun setGoogleAsDefaultAssistantOrOpenSettings(): Boolean {
        val component = findGoogleVoiceInteractionService() ?: run {
            openDefaultAssistantSettings()
            return false
        }
        val value = component.flattenToString()
        return try {
            Settings.Secure.putString(contentResolver, "assistant", value)
            Settings.Secure.putString(contentResolver, "voice_interaction_service", value)
            true
        } catch (_: SecurityException) {
            openDefaultAssistantSettings()
            false
        } catch (_: Throwable) {
            openDefaultAssistantSettings()
            false
        }
    }

    private fun findGoogleVoiceInteractionService(): ComponentName? {
        val intent = Intent("android.service.voice.VoiceInteractionService").setPackage(Config.PKG_GOOGLE)
        val services = packageManager.queryIntentServices(intent, 0)
        val service = services.firstOrNull { info ->
            info.serviceInfo?.permission == android.Manifest.permission.BIND_VOICE_INTERACTION
        }?.serviceInfo ?: services.firstOrNull()?.serviceInfo ?: return null
        return ComponentName(service.packageName, service.name)
    }

    @Composable
    fun MainScreen() {
        val context = LocalContext.current

        // Track the live XposedService binding so the activation card and the prefs store update
        // automatically when the framework attaches (or dies).
        var service by remember { mutableStateOf(App.service) }
        DisposableEffect(Unit) {
            val listener = object : App.ServiceStateListener {
                override fun onServiceStateChanged(s: XposedService?) {
                    service = s
                    settings.bind(s)
                }
            }
            App.addServiceStateListener(listener, notifyImmediately = true)
            onDispose { App.removeServiceStateListener(listener) }
        }

        // Mirror the persisted configuration into Compose state. We re-read on every recomposition
        // through the SettingsStore, which always points at whichever store is currently
        // authoritative (RemotePreferences when bound, local SharedPreferences otherwise).
        var powerMode by remember(service) {
            mutableIntStateOf(settings.getInt(Config.KEY_POWER_MODE, Config.DEFAULT_POWER_MODE))
        }
        var gestureBarEnabled by remember(service) {
            mutableStateOf(
                settings.getBoolean(
                    Config.KEY_GESTURE_BAR_ENABLED,
                    Config.DEFAULT_GESTURE_BAR_ENABLED
                )
            )
        }

        var assistantInfo by remember { mutableStateOf(getCurrentAssistantInfo()) }

        DisposableEffect(context) {
            val lifecycleOwner = context as? LifecycleOwner
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    assistantInfo = getCurrentAssistantInfo()
                }
            }
            lifecycleOwner?.lifecycle?.addObserver(observer)
            onDispose {
                lifecycleOwner?.lifecycle?.removeObserver(observer)
            }
        }

        val scrollState = rememberScrollState()

        Scaffold(containerColor = Color.White) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A)
                )
                Text(
                    text = stringResource(R.string.app_subtitle),
                    fontSize = 13.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))
                ModuleStatusCard(service)

                Spacer(modifier = Modifier.height(16.dp))
                DefaultAssistantCard(
                    assistantInfo = assistantInfo,
                    onRefresh = { assistantInfo = getCurrentAssistantInfo() }
                )

                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(
                    title = stringResource(R.string.section_power_title),
                    subtitle = stringResource(R.string.section_power_subtitle)
                )

                Spacer(modifier = Modifier.height(10.dp))
                RadioOptionCard(
                    title = stringResource(R.string.option_gemini_title),
                    subtitle = stringResource(R.string.option_gemini_subtitle),
                    iconResId = R.drawable.gemini,
                    selected = powerMode == Config.POWER_MODE_GEMINI,
                    accentColor = Color(0xFF4285F4),
                    onClick = {
                        powerMode = Config.POWER_MODE_GEMINI
                        settings.putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_GEMINI)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                RadioOptionCard(
                    title = stringResource(R.string.option_circle_title),
                    subtitle = stringResource(R.string.option_circle_subtitle),
                    iconResId = R.drawable.google,
                    selected = powerMode == Config.POWER_MODE_CIRCLE,
                    accentColor = Color(0xFF34A853),
                    onClick = {
                        powerMode = Config.POWER_MODE_CIRCLE
                        settings.putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_CIRCLE)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                RadioOptionCard(
                    title = stringResource(R.string.option_none_title),
                    subtitle = stringResource(R.string.option_none_subtitle),
                    iconResId = null,
                    selected = powerMode == Config.POWER_MODE_NONE,
                    accentColor = Color(0xFF999999),
                    onClick = {
                        powerMode = Config.POWER_MODE_NONE
                        settings.putInt(Config.KEY_POWER_MODE, Config.POWER_MODE_NONE)
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(
                    title = stringResource(R.string.section_gesture_title),
                    subtitle = stringResource(R.string.section_gesture_subtitle)
                )

                Spacer(modifier = Modifier.height(10.dp))
                ToggleCard(
                    title = stringResource(R.string.toggle_gesture_title),
                    subtitle = stringResource(R.string.toggle_gesture_subtitle),
                    checked = gestureBarEnabled,
                    onCheckedChange = {
                        gestureBarEnabled = it
                        settings.putBoolean(Config.KEY_GESTURE_BAR_ENABLED, it)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                InfoCard(
                    text = stringResource(R.string.gesture_requirement)
                )

                Spacer(modifier = Modifier.height(32.dp))
                HorizontalDivider(color = Color(0xFFEEEEEE))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.footer_effective_hint),
                    fontSize = 12.sp,
                    color = Color(0xFFFF9500),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    @Composable
    fun ModuleStatusCard(service: XposedService?) {
        val active = service != null
        val frameworkName = remember(service) {
            try {
                service?.frameworkName
            } catch (_: Throwable) {
                null
            }
        }
        val apiVersion = remember(service) {
            try {
                service?.apiVersion ?: 0
            } catch (_: Throwable) {
                0
            }
        }

        val statusColor = if (active) Color(0xFF34A853) else Color(0xFFE53935)
        val bgColor = if (active) Color(0xFFEFFAEF) else Color(0xFFFDECEA)
        val title = if (active) {
            stringResource(R.string.module_status_active_title)
        } else {
            stringResource(R.string.module_status_inactive_title)
        }
        val description = if (active) {
            val name = frameworkName ?: "libxposed"
            if (apiVersion > 0) {
                stringResource(R.string.module_status_active_desc_with_api, name, apiVersion)
            } else {
                stringResource(R.string.module_status_active_desc, name)
            }
        } else {
            stringResource(R.string.module_status_inactive_desc)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(bgColor)
                .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
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
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1A1A)
                    )
                    Text(
                        text = description,
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        }
    }

    @Composable
    fun DefaultAssistantCard(
        assistantInfo: AssistantInfo?,
        onRefresh: () -> Unit
    ) {
        val isGoogleAssistant = assistantInfo?.packageName == Config.PKG_GOOGLE
        val bgColor = if (isGoogleAssistant) Color(0xFFF0FAF0) else Color(0xFFFFF8E1)
        val borderColor = if (isGoogleAssistant) {
            Color(0xFF34A853).copy(alpha = 0.3f)
        } else {
            Color(0xFFFFA000).copy(alpha = 0.3f)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                .clickable {
                    if (isGoogleAssistant) {
                        openDefaultAssistantSettings()
                    } else if (setGoogleAsDefaultAssistantOrOpenSettings()) {
                        onRefresh()
                    }
                }
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
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
                        text = stringResource(R.string.default_assistant_label),
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                    Text(
                        text = assistantInfo?.name ?: stringResource(R.string.default_assistant_unset),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A1A)
                    )
                    if (!isGoogleAssistant) {
                        Text(
                            text = stringResource(R.string.default_assistant_warning),
                            fontSize = 11.sp,
                            color = Color(0xFFFFA000)
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.open_settings),
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
            animationSpec = tween(250),
            label = "border"
        )
        val bgColor by animateColorAsState(
            targetValue = if (selected) accentColor.copy(alpha = 0.06f) else Color(0xFFFAFAFA),
            animationSpec = tween(250),
            label = "bg"
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
                        Text(
                            text = "X",
                            fontSize = 14.sp,
                            color = Color(0xFF999999),
                            fontWeight = FontWeight.Bold
                        )
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
            animationSpec = tween(250),
            label = "toggleBorder"
        )
        val bgColor by animateColorAsState(
            targetValue = if (checked) Color(0xFFF0FAF0) else Color(0xFFFAFAFA),
            animationSpec = tween(250),
            label = "toggleBg"
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
