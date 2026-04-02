package com.redalert.watch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.pushy.sdk.Pushy
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// ── Models ────────────────────────────────────────────

enum class Screen { STANDBY, ALERT, SETTINGS }

data class AlertState(
    val active: Boolean = false,
    val city: String = "",
    val threat: String = "",
    val countdown: Int = 0,
    val triggeredAt: Long = 0L
)

data class AppPrefs(
    val watchedCities: Set<String> = emptySet(),
    val pushyToken: String? = null
)

// ── MainActivity ──────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val prefs by lazy { AppPreferences(this) }

    // AlertService.alertState מוזרם מה-PushReceiver
    companion object {
        val alertState = MutableStateFlow(AlertState())

        // כתובת השרת שלך על Render
        // שנה את זה לאחר שתעלה לשרת!
        const val SERVER_URL = "https://redalert-pushy-server.onrender.com"
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { registerWithPushy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // בקש פרמישן התראות ואז רשום Pushy
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            registerWithPushy()
        }

        // הפעל Pushy listener service
        Pushy.listen(this)

        setContent {
            val alert by alertState.collectAsState()
            val appPrefs by prefs.state.collectAsState()
            var screen by remember { mutableStateOf(Screen.STANDBY) }

            LaunchedEffect(alert.active) {
                if (alert.active) screen = Screen.ALERT
            }

            MaterialTheme {
                when (screen) {
                    Screen.STANDBY -> StandbyScreen(
                        prefs = appPrefs,
                        onSettings = { screen = Screen.SETTINGS }
                    )
                    Screen.ALERT -> AlertScreen(
                        alert = alert,
                        onDismiss = {
                            alertState.value = AlertState()
                            screen = Screen.STANDBY
                        }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        prefs = appPrefs,
                        onSave = { updated ->
                            prefs.save(updated)
                            // עדכן ערים בשרת
                            lifecycleScope.launch(Dispatchers.IO) {
                                updated.pushyToken?.let { token ->
                                    updateCitiesOnServer(token, updated.watchedCities.toList())
                                }
                            }
                            screen = Screen.STANDBY
                        }
                    )
                }
            }
        }
    }

    // ── רישום Pushy ───────────────────────────────────
    private fun registerWithPushy() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = Pushy.register(this@MainActivity)
                Log.d("Pushy", "Token: $token")

                val p = prefs.state.value
                prefs.save(p.copy(pushyToken = token))

                // שלח token + ערים לשרת
                registerOnServer(token, p.watchedCities.toList())

            } catch (e: Exception) {
                Log.e("Pushy", "Registration failed: ${e.message}")
            }
        }
    }

    private fun registerOnServer(token: String, cities: List<String>) {
        try {
            val conn = URL("$SERVER_URL/register").openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout    = 5000

            val body = JSONObject().apply {
                put("token",  token)
                put("cities", JSONArray(cities))
            }.toString()

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            Log.d("Server", "Register: HTTP $code")
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("Server", "Register failed: ${e.message}")
        }
    }

    private fun updateCitiesOnServer(token: String, cities: List<String>) {
        try {
            val conn = URL("$SERVER_URL/update-cities").openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout    = 5000

            val body = JSONObject().apply {
                put("token",  token)
                put("cities", JSONArray(cities))
            }.toString()

            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("Server", "Update cities failed: ${e.message}")
        }
    }
}

// ── AppPreferences ────────────────────────────────────

class AppPreferences(ctx: Context) {
    private val sp = ctx.getSharedPreferences("redalert_watch", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppPrefs> = _state

    fun save(p: AppPrefs) {
        sp.edit()
            .putStringSet("cities",     p.watchedCities)
            .putString("pushy_token",   p.pushyToken)
            .apply()
        _state.value = p
    }

    private fun load() = AppPrefs(
        watchedCities = sp.getStringSet("cities", emptySet()) ?: emptySet(),
        pushyToken    = sp.getString("pushy_token", null)
    )
}

// ── Screens ───────────────────────────────────────────

@Composable
fun StandbyScreen(prefs: AppPrefs, onSettings: () -> Unit) {
    val connected = prefs.pushyToken != null
    val inf = rememberInfiniteTransition(label = "p")
    val scale by inf.animateFloat(
        1f, 1.5f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s"
    )
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0C0C0C)).clickable { onSettings() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(10.dp).scale(scale).clip(CircleShape)
                    .background(if (connected) Color(0xFF00E676) else Color(0xFFFF5252))
            )
            Spacer(Modifier.height(10.dp))
            Text("פיקוד העורף", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !connected                    -> "מתחבר..."
                    prefs.watchedCities.isEmpty() -> "לחץ להגדרת ערים"
                    else -> "${prefs.watchedCities.size} ערים · פעיל"
                },
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp, textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun AlertScreen(alert: AlertState, onDismiss: () -> Unit) {
    var secsLeft by remember { mutableIntStateOf(alert.countdown) }
    LaunchedEffect(alert.triggeredAt) {
        secsLeft = alert.countdown
        while (secsLeft > 0) { kotlinx.coroutines.delay(1_000); secsLeft-- }
    }
    val inf = rememberInfiniteTransition(label = "bg")
    val bgAlpha by inf.animateFloat(
        0.85f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "a"
    )
    Box(
        Modifier.fillMaxSize().background(Color(0xFFBB0000).copy(alpha = bgAlpha))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
            Text("🚨", fontSize = 28.sp)
            Spacer(Modifier.height(3.dp))
            Text("צבע אדום", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(2.dp))
            Text(alert.city, color = Color.White.copy(0.9f), fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            if (alert.countdown > 0) {
                CountdownRing(secsLeft, alert.countdown)
                Spacer(Modifier.height(6.dp))
            }
            Text(threatToInstruction(alert.threat),
                color = Color.White, fontSize = 10.sp,
                textAlign = TextAlign.Center, maxLines = 2, lineHeight = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text("לחץ לסגור", color = Color.White.copy(0.35f), fontSize = 9.sp)
        }
    }
}

@Composable
fun CountdownRing(secsLeft: Int, total: Int) {
    val progress = if (total > 0) secsLeft.toFloat() / total else 0f
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = progress, modifier = Modifier.size(50.dp), strokeWidth = 3.dp,
            indicatorColor = Color.White, trackColor = Color.White.copy(0.15f)
        )
        Text(secsLeft.toString(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

fun threatToInstruction(threat: String) = when (threat.lowercase()) {
    "missiles", "rockets" -> "היכנס למרחב מוגן"
    "infiltration"        -> "נעל דלתות, שכב על הרצפה"
    "earthquake"          -> "עמוד בפתח דלת"
    "tsunami"             -> "התרחק מהחוף"
    else                  -> "היכנס למרחב מוגן"
}

val PRESET_CITIES = listOf(
    "תל אביב", "ירושלים", "חיפה", "באר שבע", "ראשון לציון",
    "פתח תקווה", "אשדוד", "אשקלון", "נתניה", "רמת גן",
    "גבעתיים", "בני ברק", "חולון", "בת ים", "הרצליה",
    "כפר סבא", "רעננה", "מודיעין", "רחובות", "נס ציונה",
    "עכו", "נהריה", "טבריה", "צפת", "קריית שמונה",
    "שדרות", "נתיבות", "אופקים", "דימונה", "אילת"
)

@Composable
fun SettingsScreen(prefs: AppPrefs, onSave: (AppPrefs) -> Unit) {
    var selected by remember { mutableStateOf(prefs.watchedCities.toMutableSet()) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0C0C0C)),
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text("ערים", color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center)
        }
        items(PRESET_CITIES.chunked(2).size) { i ->
            val row = PRESET_CITIES.chunked(2)[i]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { city ->
                    val active = city in selected
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (active) Color(0xFFCC0000).copy(0.85f) else Color.White.copy(0.07f))
                            .clickable {
                                selected = selected.toMutableSet().also {
                                    if (active) it.remove(city) else it.add(city)
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(city,
                            color = if (active) Color.White else Color.White.copy(0.6f),
                            fontSize = 10.sp, textAlign = TextAlign.Center,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFCC0000))
                    .clickable { onSave(prefs.copy(watchedCities = selected)) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("שמור", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
