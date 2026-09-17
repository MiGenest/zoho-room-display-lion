package com.example.roomdisplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val STATUS_URL = "https://zoho-room-display-lion.misha-9f1.workers.dev/api/status"
private const val POLL_INTERVAL_MS = 300_000L // 5 minutes
private const val CLOCK_TICK_MS = 1_000L
private const val ENDING_SOON_MINUTES = 5
private const val STALE_AFTER_SEC = 90L

private val darkBar = Color(0xFF14181D)
private val freeColor = Color(0xFF0E6B4F)
private val busyColor = Color(0xFFD42A20)
private val warningColor = Color(0xFFD97706)
private val staleTextColor = Color(0xFFEF4444)

data class RoomStatus(
    val isOccupied: Boolean,
    val currentTitle: String? = null,
    val currentOrganizer: String? = null,
    val currentStartTime: String? = null,
    val currentEndTime: String? = null,
    val nextTitle: String? = null,
    val nextOrganizer: String? = null,
    val nextStartTime: String? = null,
    val nextEndTime: String? = null,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()

        setContent {
            var status by remember { mutableStateOf<RoomStatus?>(null) }
            var lastUpdated by remember { mutableStateOf<Date?>(null) }
            var now by remember { mutableStateOf(Date()) }

            LaunchedEffect(Unit) {
                while (true) {
                    val result = fetchStatus()
                    if (result != null) {
                        status = result
                        lastUpdated = Date()
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    now = Date()
                    delay(CLOCK_TICK_MS)
                }
            }

            RoomScreen(status, now, lastUpdated)
        }
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

private suspend fun fetchStatus(): RoomStatus? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient()
        val request = Request.Builder().url(STATUS_URL).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val occupied = json.getBoolean("isOccupied")

            val current = json.optJSONObject("currentMeeting")
            val next = json.optJSONObject("nextMeeting")

            RoomStatus(
                isOccupied = occupied,
                currentTitle = current?.optString("title"),
                currentOrganizer = current?.optString("organizer")?.takeIf { it.isNotBlank() },
                currentStartTime = current?.optString("startTime"),
                currentEndTime = current?.optString("endTime"),
                nextTitle = next?.optString("title"),
                nextOrganizer = next?.optString("organizer")?.takeIf { it.isNotBlank() },
                nextStartTime = next?.optString("startTime"),
                nextEndTime = next?.optString("endTime"),
            )
        }
    } catch (e: Exception) {
        android.util.Log.e("RoomDisplay", "fetch failed", e)
        null
    }
}

private fun prettyOrganizer(email: String?): String? {
    if (email.isNullOrBlank()) return null
    val local = email.substringBefore("@")
    return local.split(".", "_").joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }
}

private fun timeStringToDateToday(hhmm: String?, reference: Date): Date? {
    if (hhmm.isNullOrBlank()) return null
    val parts = hhmm.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    val cal = Calendar.getInstance()
    cal.time = reference
    cal.set(Calendar.HOUR_OF_DAY, hour)
    cal.set(Calendar.MINUTE, minute)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.time
}

private fun agoText(lastUpdated: Date?, now: Date): String {
    if (lastUpdated == null) return "Not yet updated"
    val diffSec = ((now.time - lastUpdated.time) / 1000).coerceAtLeast(0)
    return when {
        diffSec < 60 -> "Updated ${diffSec}s ago"
        diffSec < 3600 -> "Updated ${diffSec / 60}m ago"
        else -> "Updated ${diffSec / 3600}h ago"
    }
}

@Composable
fun RoomScreen(status: RoomStatus?, now: Date, lastUpdated: Date?) {
    val minutesLeft = remember(status, now) {
        if (status?.isOccupied == true) {
            timeStringToDateToday(status.currentEndTime, now)?.let { end ->
                ((end.time - now.time) / 60000).toInt()
            }
        } else null
    }

    val endingSoon = status?.isOccupied == true && minutesLeft != null && minutesLeft in 0..ENDING_SOON_MINUTES

    val accent = when {
        status == null -> Color(0xFF37474F)
        status.isOccupied && endingSoon -> warningColor
        status.isOccupied -> busyColor
        else -> freeColor
    }

    val timeText = remember(now) { SimpleDateFormat("HH:mm", Locale.ENGLISH).format(now) }
    val dateText = remember(now) { SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(now) }

    val staleSec = lastUpdated?.let { ((now.time - it.time) / 1000) } ?: Long.MAX_VALUE
    val isStale = staleSec > STALE_AFTER_SEC

    Column(modifier = Modifier.fillMaxSize()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .background(darkBar)
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Boardroom · Lion Trans HQ", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.lion_trans_logo),
                        contentDescription = "Lion Trans",
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "LION TRANS",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {

            Column(
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
                    .background(accent)
                    .padding(horizontal = 56.dp, vertical = 48.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(timeText, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(14.dp))
                    Text(dateText, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
                }

                Text(
                    when {
                        status == null -> "..."
                        status.isOccupied && endingSoon -> "ENDING SOON"
                        status.isOccupied -> "BUSY"
                        else -> "FREE"
                    },
                    color = Color.White,
                    fontSize = if (endingSoon) 64.sp else 88.sp,
                    fontWeight = FontWeight.ExtraBold,
                )

                Spacer(Modifier.weight(1f))

                if (status != null) {
                    if (status.isOccupied) {
                        Text(status.currentTitle ?: "", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        val organizer = prettyOrganizer(status.currentOrganizer)
                        Text(
                            listOfNotNull(
                                organizer?.let { "Organizer: $it" },
                                "until ${status.currentEndTime}",
                            ).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 16.sp,
                        )
                    } else if (status.nextTitle != null) {
                        Text(
                            "Next · ${status.nextTitle} · ${status.nextStartTime}",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 15.sp,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .background(Color.White)
                    .padding(horizontal = 36.dp, vertical = 40.dp),
            ) {
                Text(
                    "TODAY",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))

                if (status?.isOccupied == true) {
                    AgendaEntry(
                        dotColor = accent,
                        timeRange = "${status.currentStartTime} → ${status.currentEndTime} · Now",
                        timeColor = accent,
                        title = status.currentTitle ?: "",
                        organizer = prettyOrganizer(status.currentOrganizer),
                    )
                    Divider()
                }

                if (status?.nextTitle != null) {
                    AgendaEntry(
                        dotColor = Color(0xFFD1D5DB),
                        timeRange = "${status.nextStartTime} → ${status.nextEndTime}",
                        timeColor = Color(0xFF6B7280),
                        title = status.nextTitle,
                        organizer = prettyOrganizer(status.nextOrganizer),
                    )
                }

                if (status != null && status.currentTitle == null && status.nextTitle == null) {
                    Text("No meetings scheduled today", color = Color(0xFF9CA3AF), fontSize = 15.sp)
                }

                Spacer(Modifier.weight(1f))

                Text(
                    agoText(lastUpdated, now),
                    color = if (isStale) staleTextColor else Color(0xFFD1D5DB),
                    fontSize = 12.sp,
                    fontWeight = if (isStale) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun AgendaEntry(dotColor: Color, timeRange: String, timeColor: Color, title: String, organizer: String?) {
    Row(modifier = Modifier.padding(bottom = 18.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(timeRange, color = timeColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(title, color = Color(0xFF1F2937), fontSize = 23.sp, fontWeight = FontWeight.Bold)
            if (organizer != null) {
                Spacer(Modifier.height(4.dp))
                Text(organizer, color = Color(0xFF6B7280), fontSize = 19.sp)
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFFE5E7EB))
            .padding(bottom = 18.dp),
    )
}
