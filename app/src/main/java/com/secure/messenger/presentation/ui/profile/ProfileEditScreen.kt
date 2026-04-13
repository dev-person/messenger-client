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
import androidx.compose.foundation.layout.Arrangement
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // ── OneUI-стиль: большой заголовок ─────────────────────────────────
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
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

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clickable { showAvatarSheet = true },
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

                    // Подсказка справа
                    Text(
                        text = "Изменить",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
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
