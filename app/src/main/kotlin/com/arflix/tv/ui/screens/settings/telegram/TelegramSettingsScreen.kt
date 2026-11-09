package com.arflix.tv.ui.screens.settings.telegram

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.R
import com.arflix.tv.data.telegram.TelegramAuthState
import com.arflix.tv.ui.components.LoadingIndicator
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.SuccessGreen
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.util.LocalDeviceType
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TelegramSettingsScreen(
    onBack: () -> Unit,
    viewModel: TelegramSettingsViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsState()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsState()
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    val backFocusRequester = remember { FocusRequester() }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshCacheSize()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                var backFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .focusRequester(backFocusRequester)
                        .onFocusChanged { backFocused = it.isFocused }
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (backFocused) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (backFocused) 2.dp else 0.dp,
                            color = if (backFocused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter)) {
                                onBack()
                                true
                            } else false
                        }
                        .focusable()
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = if (backFocused) Color.White else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Telegram",
                    style = ArflixTypography.sectionTitle,
                    color = TextPrimary
                )
            }

            val isMobile = LocalDeviceType.current.isTouchDevice()

            when (val state = authState) {
                is TelegramAuthState.Idle -> IdleContent(onConnect = { viewModel.startAuth() })
                is TelegramAuthState.Initializing -> LoadingContent(stringResource(R.string.telegram_connecting))
                is TelegramAuthState.WaitPhone -> {
                    if (isMobile) {
                        PhoneContent(onSubmit = { viewModel.submitPhone(it) })
                    } else {
                        LaunchedEffect(Unit) { viewModel.startQrAuth() }
                        LoadingContent(stringResource(R.string.telegram_preparing_qr))
                    }
                }
                is TelegramAuthState.WaitQr -> QrContent(link = state.link)
                is TelegramAuthState.WaitCode -> CodeContent(
                    codeLength = state.codeLength,
                    onSubmit = { viewModel.submitCode(it) },
                    backFocusRequester = backFocusRequester
                )
                is TelegramAuthState.WaitPassword -> PasswordContent(
                    onSubmit = { viewModel.submitPassword(it) },
                    backFocusRequester = backFocusRequester
                )
                is TelegramAuthState.Ready -> ConnectedContent(
                    firstName = state.firstName,
                    cacheSizeBytes = cacheSizeBytes,
                    onDisconnect = { showDisconnectConfirm = true },
                    onClearCache = { viewModel.clearCache() }
                )
                is TelegramAuthState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.startAuth() }
                )
            }
        }
        } // inner padded Box

        if (showDisconnectConfirm) {
            DisconnectConfirmDialog(
                onConfirm = {
                    showDisconnectConfirm = false
                    viewModel.disconnect()
                },
                onDismiss = { showDisconnectConfirm = false }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IdleContent(onConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = "Conexão Necessária",
            style = ArflixTypography.cardTitle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "O Fênix + funciona como um cliente alternativo do Telegram, projetado com uma interface focada exclusivamente em filmes e séries, além de um player de vídeo aprimorado. Para acessar o catálogo completo e as funcionalidades do bot, você precisa conectar sua conta.",
            style = ArflixTypography.caption.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(Color(0xFF0088CC).copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF0088CC).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Suas mensagens privadas não são acessadas. O aplicativo apenas utiliza a API do Telegram para localizar e transmitir os arquivos de mídia do Bot Fênix.",
                style = ArflixTypography.caption.copy(fontSize = 12.sp),
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        ActionButton(label = "CONECTAR TELEGRAM", onClick = onConnect)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(56.dp))
        LoadingIndicator(color = Pink, size = 40.dp, strokeWidth = 3.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = ArflixTypography.caption.copy(fontSize = 14.sp), color = TextSecondary)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QrContent(link: String) {
    val qrBitmap = remember(link) { generateQrBitmap(link, 400) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.telegram_scan_title),
            style = ArflixTypography.cardTitle.copy(fontSize = 20.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.telegram_scan_instructions),
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (qrBitmap != null) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.telegram_qr_code_desc),
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            LoadingIndicator(color = Pink, size = 40.dp, strokeWidth = 3.dp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.telegram_qr_expires),
            style = ArflixTypography.caption.copy(fontSize = 11.sp),
            color = TextSecondary.copy(alpha = 0.6f)
        )
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? = try {
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bmp
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e

    null
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PhoneContent(onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("+") }
    var isSubmitting by remember { mutableStateOf(false) }
    var showValidation by remember { mutableStateOf(false) }

    val digits = phone.filter { it.isDigit() }
    val isValid = phone.startsWith("+") && digits.length >= 7
    val showError = showValidation && !isValid

    fun trySubmit() {
        showValidation = true
        if (isValid && !isSubmitting) {
            isSubmitting = true
            onSubmit(phone)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = stringResource(R.string.telegram_phone_title),
            style = ArflixTypography.cardTitle.copy(fontSize = 22.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.telegram_phone_desc),
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text(stringResource(R.string.telegram_phone_label)) },
            placeholder = { Text("+1 650 555 1234", color = TextSecondary.copy(alpha = 0.35f)) },
            supportingText = {
                if (showError) {
                    Text(stringResource(R.string.telegram_phone_error), color = Pink)
                } else {
                    Text(stringResource(R.string.telegram_phone_format), color = TextSecondary.copy(alpha = 0.5f))
                }
            },
            isError = showError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { trySubmit() }),
            singleLine = true,
            colors = inputColors(),
            modifier = Modifier.fillMaxWidth(0.75f)
        )
        Spacer(modifier = Modifier.height(20.dp))
        if (isSubmitting) {
            LoadingIndicator(color = Pink, size = 32.dp, strokeWidth = 2.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.telegram_sending_code),
                style = ArflixTypography.caption.copy(fontSize = 13.sp),
                color = TextSecondary
            )
        } else {
            ActionButton(label = stringResource(R.string.telegram_send_code), onClick = ::trySubmit)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CodeContent(
    codeLength: Int,
    onSubmit: (String) -> Unit,
    backFocusRequester: FocusRequester? = null
) {
    var code by remember { mutableStateOf("") }
    val confirmFocusRequester = remember { FocusRequester() }
    val isTouchDevice = LocalDeviceType.current.isTouchDevice()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Row(
            modifier = Modifier
                .background(SuccessGreen.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .border(1.dp, SuccessGreen.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "✓  ", style = ArflixTypography.caption.copy(fontSize = 14.sp), color = SuccessGreen)
            Text(
                text = stringResource(R.string.telegram_code_sent),
                style = ArflixTypography.caption.copy(fontSize = 13.sp),
                color = SuccessGreen
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.telegram_code_title),
            style = ArflixTypography.cardTitle.copy(fontSize = 22.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.telegram_code_desc, codeLength),
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (isTouchDevice) {
            TextField(
                value = code,
                onValueChange = { if (it.length <= codeLength) code = it },
                label = { Text(stringResource(R.string.telegram_code_label)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { if (code.length == codeLength) onSubmit(code) }),
                singleLine = true,
                colors = inputColors(),
                modifier = Modifier.fillMaxWidth(0.55f)
            )
        } else {
            TvInputField(
                value = code,
                onValueChange = { if (it.length <= codeLength) code = it },
                hint = stringResource(R.string.telegram_code_label),
                isNumeric = true,
                maxLength = codeLength,
                modifier = Modifier.fillMaxWidth(0.55f),
                onConfirm = { if (code.isNotBlank()) onSubmit(code) },
                confirmFocusRequester = confirmFocusRequester,
                backFocusRequester = backFocusRequester
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        ActionButton(
            label = stringResource(R.string.confirm).uppercase(),
            modifier = Modifier.focusRequester(confirmFocusRequester),
            onClick = { if (code.isNotBlank()) onSubmit(code) }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PasswordContent(
    onSubmit: (String) -> Unit,
    backFocusRequester: FocusRequester? = null
) {
    var password by remember { mutableStateOf("") }
    val confirmFocusRequester = remember { FocusRequester() }
    val isTouchDevice = LocalDeviceType.current.isTouchDevice()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = stringResource(R.string.telegram_2fa_title),
            style = ArflixTypography.cardTitle.copy(fontSize = 20.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.telegram_2fa_desc),
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (isTouchDevice) {
            TextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.telegram_password_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit(password) }),
                singleLine = true,
                colors = inputColors(),
                modifier = Modifier.fillMaxWidth(0.55f)
            )
        } else {
            TvInputField(
                value = password,
                onValueChange = { password = it },
                hint = stringResource(R.string.telegram_password_label),
                isPassword = true,
                modifier = Modifier.fillMaxWidth(0.55f),
                onConfirm = { onSubmit(password) },
                confirmFocusRequester = confirmFocusRequester,
                backFocusRequester = backFocusRequester
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        ActionButton(
            label = stringResource(R.string.confirm).uppercase(),
            modifier = Modifier.focusRequester(confirmFocusRequester),
            onClick = { onSubmit(password) }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectedContent(
    firstName: String,
    cacheSizeBytes: Long,
    onDisconnect: () -> Unit,
    onClearCache: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SuccessGreen.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .border(1.dp, SuccessGreen.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.connected),
                    style = ArflixTypography.cardTitle.copy(fontSize = 15.sp),
                    color = SuccessGreen
                )
                Text(
                    text = stringResource(R.string.telegram_signed_in_as, firstName),
                    style = ArflixTypography.caption.copy(fontSize = 13.sp),
                    color = TextSecondary
                )
            }
            var disconnectFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .onFocusChanged { disconnectFocused = it.isFocused }
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (disconnectFocused) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = if (disconnectFocused) 2.dp else 0.dp,
                        color = if (disconnectFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter)) {
                            onDisconnect()
                            true
                        } else false
                    }
                    .focusable()
                    .clickable { onDisconnect() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.telegram_disconnect_btn),
                    style = ArflixTypography.label.copy(fontSize = 11.sp),
                    color = if (disconnectFocused) Color.White else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        var cacheFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { cacheFocused = it.isFocused }
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (cacheFocused) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f),
                    RoundedCornerShape(12.dp)
                )
                .border(
                    width = if (cacheFocused) 2.dp else 1.dp,
                    color = if (cacheFocused) Color.White else Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp)
                )
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter)) {
                        if (cacheSizeBytes > 0L) onClearCache()
                        true
                    } else false
                }
                .focusable()
                .clickable { if (cacheSizeBytes > 0L) onClearCache() }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.telegram_video_cache),
                        style = ArflixTypography.cardTitle.copy(fontSize = 14.sp),
                        color = if (cacheFocused) Color.White else TextPrimary
                    )
                    Text(
                        text = formatCacheSize(cacheSizeBytes),
                        style = ArflixTypography.caption.copy(fontSize = 13.sp),
                        color = TextSecondary
                    )
                }
                if (cacheSizeBytes > 0L) {
                    Text(
                        text = stringResource(R.string.telegram_clear_cache_btn),
                        style = ArflixTypography.label.copy(fontSize = 11.sp),
                        color = if (cacheFocused) Color.White else Pink
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Pink, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = stringResource(R.string.telegram_connection_failed), style = ArflixTypography.cardTitle.copy(fontSize = 18.sp), color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = ArflixTypography.caption.copy(fontSize = 13.sp), color = TextSecondary)
        Spacer(modifier = Modifier.height(24.dp))
        ActionButton(label = stringResource(R.string.telegram_try_again), onClick = onRetry)
    }
}

@Composable
private fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (isFocused) Pink.copy(alpha = 0.35f) else Pink.copy(alpha = 0.15f),
                RoundedCornerShape(999.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color.White else Pink.copy(alpha = 0.4f),
                shape = RoundedCornerShape(999.dp)
            )
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter)) {
                    onClick()
                    true
                } else false
            }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = ArflixTypography.label.copy(fontSize = 13.sp, letterSpacing = 0.8.sp),
            color = if (isFocused) Color.White else Pink
        )
    }
}

@Composable
private fun TvInputField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean = false,
    isNumeric: Boolean = false,
    maxLength: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit = {},
    confirmFocusRequester: FocusRequester? = null,
    backFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    var editTextRef by remember { mutableStateOf<EditText?>(null) }
    var isInputFocused by remember { mutableStateOf(false) }
    var isBoxFocused by remember { mutableStateOf(false) }
    val isHighlighted = isInputFocused || isBoxFocused

    fun hideKeyboard(editText: EditText? = editTextRef) {
        editText?.post {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(editText.windowToken, 0)
            editText.clearFocus()
        }
    }

    fun showKeyboard(editText: EditText) {
        editText.post {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val shown = imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) ?: false
            if (!shown) {
                @Suppress("DEPRECATION")
                imm?.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
            }
        }
    }

    Box(
        modifier = modifier
            .onFocusChanged { state ->
                isBoxFocused = state.isFocused
            }
            .focusable()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF222222))
            .border(
                width = if (isHighlighted) 2.dp else 1.dp,
                color = if (isHighlighted) Color.White else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable {
                editTextRef?.let { et ->
                    et.requestFocus()
                    showKeyboard(et)
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    editTextRef = this
                    setText(value)
                    setTextColor(android.graphics.Color.WHITE)
                    setHintTextColor(android.graphics.Color.GRAY)
                    setHint(hint)
                    textSize = 16f
                    background = null
                    setPadding(32, 28, 32, 28)
                    isSingleLine = true
                    inputType = when {
                        isPassword -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        isNumeric -> InputType.TYPE_CLASS_NUMBER
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                    if (isPassword) {
                        transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                    }
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    isFocusable = true
                    isFocusableInTouchMode = true
                    doAfterTextChanged { editable ->
                        val text = editable?.toString() ?: ""
                        if (text.length <= maxLength) {
                            onValueChange(text)
                        } else {
                            setText(text.take(maxLength))
                            setSelection(maxLength)
                        }
                    }
                    setOnFocusChangeListener { _, hasFocus ->
                        isInputFocused = hasFocus
                    }
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                            when (keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    hideKeyboard(this)
                                    clearFocus()
                                    runCatching { confirmFocusRequester?.requestFocus() }
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                    hideKeyboard(this)
                                    clearFocus()
                                    runCatching { backFocusRequester?.requestFocus() }
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                                    showKeyboard(this)
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_BACK -> {
                                    hideKeyboard(this)
                                    clearFocus()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
                    setOnEditorActionListener { _, actionId, event ->
                        val isDoneAction = actionId == EditorInfo.IME_ACTION_DONE
                        val isEnterKey = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP
                        if (isDoneAction || isEnterKey) {
                            hideKeyboard(this)
                            clearFocus()
                            if (value.isNotBlank()) {
                                onConfirm()
                            } else {
                                runCatching { confirmFocusRequester?.requestFocus() }
                            }
                            true
                        } else false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            update = { et ->
                if (et.text.toString() != value) {
                    et.setText(value)
                    et.setSelection(value.length)
                }
            }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DisconnectConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var focusedButton by remember { mutableIntStateOf(0) } // 0 = cancel, 1 = disconnect
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .background(BackgroundElevated, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Back, Key.Escape -> { onDismiss(); true }
                        Key.DirectionLeft  -> { focusedButton = if (isRtl) 1 else 0; true }
                        Key.DirectionRight -> { focusedButton = if (isRtl) 0 else 1; true }
                        Key.Enter, Key.DirectionCenter -> {
                            if (focusedButton == 0) onDismiss() else onConfirm()
                            true
                        }
                        else -> false
                    }
                }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.telegram_disconnect_confirm_title),
                style = ArflixTypography.cardTitle.copy(fontSize = 18.sp),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.telegram_disconnect_confirm_desc),
                style = ArflixTypography.caption.copy(fontSize = 13.sp),
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (focusedButton == 0) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (focusedButton == 0) 2.dp else 0.dp,
                            color = if (focusedButton == 0) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onDismiss() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.cancel).uppercase(),
                        style = ArflixTypography.label.copy(fontSize = 12.sp),
                        color = if (focusedButton == 0) Color.White else TextPrimary
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (focusedButton == 1) Pink.copy(alpha = 0.3f) else Pink.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (focusedButton == 1) 2.dp else 1.dp,
                            color = if (focusedButton == 1) Color.White else Pink.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onConfirm() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.telegram_disconnect_btn),
                        style = ArflixTypography.label.copy(fontSize = 12.sp),
                        color = if (focusedButton == 1) Color.White else Pink
                    )
                }
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private fun formatLimitMb(mb: Long): String = when {
    mb >= 1024 -> "%.1f GB".format(mb / 1024.0)
    else -> "$mb MB"
}

@Composable
private fun inputColors() = TextFieldDefaults.colors(
    focusedContainerColor = BackgroundElevated,
    unfocusedContainerColor = BackgroundElevated,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = Pink,
    unfocusedLabelColor = TextSecondary
)
