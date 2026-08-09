package com.arflix.tv.ui.screens.crash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.MainActivity
import com.arflix.tv.ui.theme.ArflixTvTheme
import com.arflix.tv.util.DeviceType
import com.arflix.tv.util.detectDeviceType
import com.arflix.tv.util.deviceHasTouchScreen
import java.text.SimpleDateFormat
import java.util.*

class CrashReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("fenix_crash_store", Context.MODE_PRIVATE)
        val crashId = intent.getStringExtra(EXTRA_CRASH_ID) ?: prefs.getString("last_crash_id", "N/A") ?: "N/A"
        val crashMsg = intent.getStringExtra(EXTRA_CRASH_MSG) ?: prefs.getString("last_crash_msg", "Unexpected error") ?: "Unexpected error"
        val crashTime = intent.getLongExtra(EXTRA_CRASH_TIME, prefs.getLong("last_crash_time", System.currentTimeMillis()))
        val crashVersion = prefs.getString("last_crash_version", "1.0") ?: "1.0"

        setContent {
            ArflixTvTheme {
                CrashReportScreen(
                    crashId = crashId,
                    crashMsg = crashMsg,
                    crashTime = crashTime,
                    crashVersion = crashVersion,
                    onRestartApp = {
                        val restartIntent = Intent(this@CrashReportActivity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        startActivity(restartIntent)
                        finish()
                    },
                    onClose = { finishAffinity() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_CRASH_ID = "extra_crash_id"
        const val EXTRA_CRASH_MSG = "extra_crash_msg"
        const val EXTRA_CRASH_TIME = "extra_crash_time"
    }
}

@Composable
fun CrashReportScreen(
    crashId: String,
    crashMsg: String,
    crashTime: Long,
    crashVersion: String,
    onRestartApp: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val deviceType = detectDeviceType(context)
    val hasTouch = deviceHasTouchScreen(context)
    val isTv = deviceType == DeviceType.TV || !hasTouch

    val restartFocusRequester = remember { FocusRequester() }
    var isRestartFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isTv) {
        if (isTv) {
            runCatching { restartFocusRequester.requestFocus() }
        }
    }

    val timeString = remember(crashTime) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.format(Date(crashTime))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isTv) 760.dp else 480.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚠️ FÊNIX + Encontrou um Erro",
                color = Color.White,
                fontSize = if (isTv) 26.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Lamentamos a interrupção. Você pode reiniciar o aplicativo para tentar novamente.",
                color = Color(0xFFA0A6B2),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            // Crash Details Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF181C24),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Detalhes do Erro",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ID: $crashId\nHora: $timeString\nVersão: $crashVersion\nErro: $crashMsg",
                        color = Color(0xFFD0D5DD),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                Button(
                    onClick = onRestartApp,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRestartFocused) Color.White else Color(0xFFFF8C00)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(if (isTv) 0.6f else 1f)
                        .focusRequester(restartFocusRequester)
                        .onFocusChanged { isRestartFocused = it.isFocused }
                        .then(
                            if (isRestartFocused) {
                                Modifier.border(2.dp, Color(0xFFFF8C00), RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Text("Reiniciar FÊNIX +", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
