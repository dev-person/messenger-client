package com.secure.messenger.presentation.ui.groups

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secure.messenger.presentation.ui.components.AvatarCropDialog
import com.secure.messenger.presentation.viewmodel.CreateGroupViewModel
import java.io.ByteArrayOutputStream

/**
 * Экран создания группы. Поток: ввод названия → выбор участников
 * (с поиском) → кнопка «Создать».
 *
 * Аватар в 1.0.68 не выбирается — это фича 1.0.69; в групповых списках
 * будет показываться заглушка с инициалом названия (как у DIRECT-чатов
 * без фото).
 */
@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onGroupCreated: (chatId: String) -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Картинка, которую сейчас крутят в AvatarCropDialog. null — кропер закрыт.
    var cropSource by remember { mutableStateOf<Bitmap?>(null) }

    // Лаунчер галереи: получает Uri → декодируем в Bitmap → показываем кропер.
    val pickAvatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri),
                ) { decoder, _, _ -> decoder.isMutableRequired = true }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        }.getOrNull()
        if (bmp != null) cropSource = bmp
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.createdChatId) {
        uiState.createdChatId?.let(onGroupCreated)
    }

    // Фильтр по поиску — по displayName, phone, username
    val q = uiState.searchQuery.trim().lowercase()
    val filteredContacts = remember(contacts, q) {
        // ViewModel уже отдаёт только зарегистрированных контактов (склеив
        // их с UserEntity), поэтому здесь только текстовая фильтрация.
        if (q.isEmpty()) contacts else contacts.filter {
            it.displayName.lowercase().contains(q) ||
                it.phone.lowercase().contains(q) ||
                it.username.lowercase().contains(q)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ── Шапка ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Новая группа",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val count = uiState.selectedContactIds.size
                    if (count > 0) {
                        Text(
                            text = "$count выбрано",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // CTA создания — активен только когда есть title и хотя бы один участник
                val canCreate = uiState.title.isNotBlank() &&
                    uiState.selectedContactIds.isNotEmpty() &&
                    !uiState.isCreating
                TextButton(
                    onClick = { viewModel.createGroup() },
                    enabled = canCreate,
                ) {
                    if (uiState.isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Создать")
                    }
                }
            }

            // ── Аватар + название группы в одну строку ────────────────────────
            //   Тап по аватару открывает галерею; после выбора показывается
            //   AvatarCropDialog (см. рендер ниже). Готовый JPEG лежит в
            //   uiState.avatarBytes — превью рисуем тут же.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarPickerCircle(
                    pickedBytes = uiState.avatarBytes,
                    onClick = { pickAvatarLauncher.launch("image/*") },
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = viewModel::onTitleChange,
                    label = { Text("Название группы") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Поиск по контактам ───────────────────────────────────────────
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Поиск контактов") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(4.dp))

            // ── Список контактов ─────────────────────────────────────────────
            if (filteredContacts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (q.isNotEmpty()) "Ничего не найдено"
                               else "Нет зарегистрированных контактов",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(filteredContacts, key = { it.id }) { contact ->
                        val selected = contact.id in uiState.selectedContactIds
                        ContactRow(
                            displayName = contact.displayName,
                            phone = contact.phone,
                            avatarUrl = contact.avatarUrl,
                            selected = selected,
                            onClick = { viewModel.toggleContact(contact.id) },
                        )
                    }
                }
            }
        }
    }

    // Кропер поверх Scaffold — full-screen overlay. После confirm — JPEG
    // лежит в ViewModel state, а сам кропер закрывается.
    cropSource?.let { source ->
        AvatarCropDialog(
            sourceBitmap = source,
            onConfirm = { cropped ->
                viewModel.onAvatarPicked(cropped.toJpegBytes())
                cropSource = null
            },
            onCancel = { cropSource = null },
        )
    }
}

/**
 * Круглый плейсхолдер аватара группы. Если [pickedBytes] != null — показываем
 * превью; иначе — иконку «добавить фото». Тап вызывает [onClick] (галерея).
 */
@Composable
private fun AvatarPickerCircle(
    pickedBytes: ByteArray?,
    onClick: () -> Unit,
) {
    val previewBitmap = remember(pickedBytes) {
        pickedBytes?.let {
            android.graphics.BitmapFactory
                .decodeByteArray(it, 0, it.size)
                ?.asImageBitmap()
        }
    }
    val circleModifier = Modifier
        .size(56.dp)
        .clip(CircleShape)
        .clickable(onClick = onClick)

    if (previewBitmap != null) {
        Image(
            bitmap = previewBitmap,
            contentDescription = "Аватар группы",
            contentScale = ContentScale.Crop,
            modifier = circleModifier,
        )
    } else {
        Box(
            modifier = circleModifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.AddAPhoto,
                contentDescription = "Выбрать аватар",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Сжимает Bitmap в JPEG-байты. Качество 85 — компромисс размер/качество. */
private fun Bitmap.toJpegBytes(quality: Int = 85): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

@Composable
private fun ContactRow(
    displayName: String,
    phone: String,
    avatarUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Единый стиль аватара — фото если есть, иначе дефолт-медведь
        // на палитро-цветном фоне (см. AvatarImage в списке чатов).
        com.secure.messenger.presentation.ui.chat.AvatarImage(
            url = avatarUrl,
            name = displayName,
            size = 40,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = phone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onClick() },
        )
    }
}
