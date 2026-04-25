package com.secure.messenger.presentation.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.secure.messenger.BuildConfig
import com.secure.messenger.presentation.ui.chat.AvatarImage
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    showBackButton: Boolean = true,
    // Колбэк к родителю (HomeScreen) — поднимаем запрос на кроп туда, чтобы
    // оверлей мог покрыть И bottomBar Scaffold-а. Локально (внутри ProfileEditScreen)
    // оверлей был ограничен content area Scaffold-а и не доходил до нижней навигации.
    onRequestCrop: (Bitmap, (Bitmap) -> Unit) -> Unit = { _, _ -> },
    viewModel: ProfileEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showAvatarSheet by remember { mutableStateOf(false) }
    var avatarExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Общий колбэк после успешного кропа: уже квадратный 512×512 bitmap
    // упаковываем в JPEG и грузим на сервер.
    val onCroppedReady: (Bitmap) -> Unit = { cropped ->
        viewModel.uploadAvatar(cropped.toJpegBytes(), "jpg")
    }

    // ── Лаунчер галереи ──────────────────────────────────────────────────────
    // После выбора картинки поднимаем запрос на кроп в HomeScreen — там
    // оверлей рисуется ВЫШЕ Scaffold-а и накрывает в т.ч. bottomBar.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        onRequestCrop(raw, onCroppedReady)
    }

    // ── Лаунчер камеры ─────────────────────────────────────────────────────
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap ?: return@rememberLauncherForActivityResult
        onRequestCrop(bitmap, onCroppedReady)
    }

    // ── Лаунчер разрешения камеры ─────────────────────────────────────────
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    // ── Диалог подтверждения выхода ───────────────────────────────────────────
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Вы уверены, что хотите выйти?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout(onLogout)
                }) {
                    Text("Выйти", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Отмена") }
            },
        )
    }

    // ── Fullscreen-просмотр своего аватара ──────────────────────────────────
    // Раскрытие аватара — inline-эффект на самой карточке (не overlay), см.
    // ниже состояние avatarExpanded и анимацию height/corner/offset.

    // System back — сворачивает развёрнутый аватар, а не уходит со страницы.
    androidx.activity.compose.BackHandler(enabled = avatarExpanded) {
        avatarExpanded = false
    }

    // Status-bar: иконки белые когда фото развёрнуто (фото тёмное),
    // обратно к теме при свёрнутом. Восстанавливаем дефолт при уходе с экрана.
    val view = androidx.compose.ui.platform.LocalView.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    androidx.compose.runtime.DisposableEffect(avatarExpanded) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view)
        }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = if (avatarExpanded) false else !isDarkTheme
        onDispose {
            previous?.let { controller?.isAppearanceLightStatusBars = it }
        }
    }

    // ── Шторка выбора источника аватара ──────────────────────────────────────
    if (showAvatarSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAvatarSheet = false },
            sheetState = sheetState,
        ) {
            Text(
                text = "Изменить фото",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Сделать фото") },
                leadingContent = {
                    Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        showAvatarSheet = false
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            cameraLauncher.launch(null)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Выбрать из галереи") },
                leadingContent = {
                    Icon(Icons.Default.Photo, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        showAvatarSheet = false
                        galleryLauncher.launch("image/*")
                    }
                },
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Анимация раскрытия аватара (Telegram-style). При expanded:
    //  • Surface-header «Мой профиль» сворачивается;
    //  • карточка-аватар растягивается до 50% высоты экрана и заезжает
    //    поверх статус-бара (за счёт того, что верхний header исчезает,
    //    а у самой карточки нет statusBarsPadding);
    //  • скругление углов карточки уходит в 0 — фото становится «во всё окно».
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val expandedHeightDp = (configuration.screenHeightDp * 0.5f).dp
    val avatarHeight by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (avatarExpanded) expandedHeightDp else 200.dp,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 320,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "avatar-height",
    )
    val avatarCorner by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (avatarExpanded) 0.dp else 18.dp,
        animationSpec = androidx.compose.animation.core.tween(320),
        label = "avatar-corner",
    )
    // Боковой отступ контейнера: при expanded убираем, чтобы карточка
    // занимала всю ширину экрана без полей слева/справа.
    val containerHorizontalPad by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (avatarExpanded) 0.dp else 16.dp,
        animationSpec = androidx.compose.animation.core.tween(320),
        label = "container-pad",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // ── OneUI-стиль: большой заголовок ─────────────────────────────────
        //  Скрывается когда аватар развернут — освобождает status-bar и
        //  визуально передаёт его карточке-аватару.
        androidx.compose.animation.AnimatedVisibility(
            visible = !avatarExpanded,
            enter = androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.shrinkVertically(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 20.dp),
                ) {
                    Text(
                        text = "Мой профиль",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = containerHorizontalPad, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Аватар на всю карточку как фон ──────────────────────────
            val serverRoot = BuildConfig.API_BASE_URL.removeSuffix("v1/").trimEnd('/')
            val resolvedAvatarUrl = state.avatarUrl?.let { url ->
                when {
                    url.startsWith("http") -> url
                    url.startsWith("/") -> "$serverRoot$url"
                    else -> url
                }
            }

            // Свайп ВНИЗ по карточке-аватару — свернуть. Триггер: totalDrag
            // больше 20% expandedHeight (~10% высоты экрана). При движении
            // карточка визуально следует за пальцем (translationY) — это даёт
            // тактильный feedback, без него пользователю казалось, что свайп
            // вообще не реагирует.
            val swipeThresholdPx = with(LocalDensity.current) {
                expandedHeightDp.toPx() * 0.20f
            }
            val dragY = remember { androidx.compose.animation.core.Animatable(0f) }
            val swipeScope = rememberCoroutineScope()
            // Сброс смещения при ручном collapse (через тап/back), иначе
            // следующее открытие началось бы с уже сдвинутой карточки.
            LaunchedEffect(avatarExpanded) {
                if (!avatarExpanded) dragY.snapTo(0f)
            }
            Surface(
                shape = RoundedCornerShape(avatarCorner),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(avatarHeight)
                    .graphicsLayer { translationY = dragY.value }
                    .pointerInput(avatarExpanded) {
                        if (!avatarExpanded) return@pointerInput
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, delta ->
                                if (delta > 0 || totalDrag > 0) {
                                    totalDrag = (totalDrag + delta).coerceAtLeast(0f)
                                    swipeScope.launch { dragY.snapTo(totalDrag) }
                                    // Помечаем event'ы как использованные —
                                    // иначе родительский verticalScroll
                                    // ниже мог перехватывать жест.
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                if (totalDrag > swipeThresholdPx) {
                                    avatarExpanded = false
                                } else {
                                    swipeScope.launch {
                                        dragY.animateTo(
                                            0f,
                                            androidx.compose.animation.core.tween(180),
                                        )
                                    }
                                }
                                totalDrag = 0f
                            },
                            onDragCancel = {
                                swipeScope.launch {
                                    dragY.animateTo(
                                        0f,
                                        androidx.compose.animation.core.tween(180),
                                    )
                                }
                                totalDrag = 0f
                            },
                        )
                    }
                    // Тап: если есть аватар — раскрываем/сворачиваем карточку
                    // (Telegram-style); если фото нет — сразу bottom sheet
                    // с выбором фото (нечего показывать в полном размере).
                    .clickable {
                        if (resolvedAvatarUrl != null) {
                            avatarExpanded = !avatarExpanded
                        } else {
                            showAvatarSheet = true
                        }
                    },
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Фоновое фото
                    if (state.isLoading && state.avatarUrl == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    } else if (resolvedAvatarUrl != null) {
                        AsyncImage(
                            model = resolvedAvatarUrl,
                            contentDescription = "Аватар",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        // Фон-заглушка с инициалами
                        val initials = state.displayName.ifEmpty { state.phone }
                            .split(" ").take(2)
                            .joinToString("") { it.firstOrNull()?.uppercaseChar()?.toString() ?: "" }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Person, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    }

                    // Затемнение снизу для текста
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.6f),
                                )
                            ),
                    )

                    // Имя и username внизу
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = state.displayName.ifEmpty { "—" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        if (state.username.isNotEmpty()) {
                            Text(
                                text = "@${state.username}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }

                    // Подсказка справа — кликабельная: тап по карточке
                    // открывает/закрывает inline-просмотр аватара; изменение
                    // вызывается отдельным нажатием по этой плашке.
                    //
                    // Когда карточка свёрнута (200dp) — плашка в правом
                    // ВЕРХНЕМ углу. При expanded карточка заезжает поверх
                    // status-bar, и плашка в TopEnd оказалась бы прямо под
                    // часами/иконками сети — это нечитаемо. Переносим её в
                    // правый НИЖНИЙ угол: имя/username слева внизу, плашка
                    // справа внизу, ничего не наезжает на системную шторку.
                    Text(
                        text = "Изменить",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier
                            .align(if (avatarExpanded) Alignment.BottomEnd else Alignment.TopEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.4f))
                            .clickable { showAvatarSheet = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            // ── Аккаунт (телефон) ─────────────────────────────────────────
            if (state.phone.isNotEmpty()) {
                OneUiSectionLabel("Аккаунт")

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Phone, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.phone,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Телефон",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            // ── Основная информация ───────────────────────────────────────
            OneUiSectionLabel("Основная информация")

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = viewModel::onDisplayNameChange,
                        label = { Text("Имя") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                        isError = state.error != null && state.displayName.isBlank(),
                    )

                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::onUsernameChange,
                        label = { Text("Username") },
                        prefix = {
                            Text("@", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                        supportingText = { Text("Необязательно", style = MaterialTheme.typography.labelSmall) },
                    )

                    OutlinedTextField(
                        value = state.bio,
                        onValueChange = viewModel::onBioChange,
                        label = { Text("О себе") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )

                    if (state.error != null) {
                        Text(
                            text = state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    if (state.saved) {
                        Text(
                            text = "✓ Сохранено",
                            color = Color(0xFF4CAF50),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    Button(
                        onClick = { viewModel.save() },
                        enabled = !state.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Сохранить", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── Выход из аккаунта ─────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogoutDialog = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Выйти из аккаунта",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── OneUI-компоненты ─────────────────────────────────────────────────────────

@Composable
private fun OneUiSectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
    )
}

// ── Подготовка аватара ────────────────────────────────────────────────────────

private fun Bitmap.prepareForUpload(maxSize: Int = 512): Bitmap {
    val side = minOf(width, height)
    val x = (width - side) / 2
    val y = (height - side) / 2
    val squared = Bitmap.createBitmap(this, x, y, side, side)
    return if (side <= maxSize) squared
    else Bitmap.createScaledBitmap(squared, maxSize, maxSize, true)
}

private fun Bitmap.toJpegBytes(quality: Int = 85): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
