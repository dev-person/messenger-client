package com.secure.messenger.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secure.messenger.data.local.KeyChangeTracker
import com.secure.messenger.utils.SafetyNumber

/**
 * Диалог safety code: показывает 60-значный отпечаток обоих публичных
 * ключей. Если пара пользователей сверила коды вживую — каждый знает,
 * что переписку шифрует именно тот, с кем кажется, а не злоумышленник
 * посередине.
 *
 * Это опциональная фича — для большинства пользователей криптография
 * работает прозрачно и без участия. Сюда заходят те, кому важна особенно
 * высокая степень защиты (журналисты, юристы, и т.д.).
 *
 * Открывается из профиля собеседника: тап «Сверить код безопасности».
 */
@Composable
fun SafetyCodeDialog(
    partnerName: String,
    myPublicKey: String,
    theirPublicKey: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val code = remember(myPublicKey, theirPublicKey) {
        SafetyNumber.compute(myPublicKey, theirPublicKey)
    }
    val verified = remember(code) { KeyChangeTracker.isVerified(context, code) }
    var nowVerified by remember { mutableStateOf(verified) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (nowVerified) Icons.Filled.VerifiedUser else Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Код безопасности") },
        text = {
            val scroll = rememberScrollState()
            Column(modifier = Modifier.verticalScroll(scroll)) {
                // ── Зачем ─────────────────────────────────────────────
                Text(
                    text = "Зачем это нужно",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Ваша переписка с $partnerName зашифрована - никто, " +
                        "включая нас, её не читает в открытом виде. Однако в " +
                        "теории кто-то может попытаться подменить ключ шифрования, " +
                        "чтобы прочитать переписку. Сверка кода ниже подтверждает, " +
                        "что вы переписываетесь именно с $partnerName, а не с " +
                        "посредником.",
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(14.dp))

                // ── Кто и как может подменить ────────────────────────
                Text(
                    text = "Кто и как может подменить ключ",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "• Если наши серверы будут взломаны - злоумышленник " +
                        "сможет отдать вашему приложению свой ключ вместо ключа " +
                        "$partnerName. Тогда вы будете шифровать сообщения " +
                        "злоумышленнику, а не собеседнику.\n" +
                        "• Если кто-то контролирует ваше интернет-соединение " +
                        "(публичный Wi-Fi, провайдер, госструктура) - он " +
                        "теоретически может попытаться подменить трафик с нашими " +
                        "серверами и тоже подсунуть свой ключ.\n" +
                        "• Сам $partnerName может переустановить приложение или " +
                        "сменить пароль - это нормальная ситуация, ключ обновится " +
                        "законно. Сверка позволит понять, какая из ситуаций.",
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(14.dp))

                // ── Можно ли обмануть код ────────────────────────────
                Text(
                    text = "Можно ли обмануть код",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Цифры считаются на вашем телефоне из реальных ключей, " +
                        "которые видит ваше приложение. Если кто-то подменил ключ " +
                        "собеседника, у вас и у него получатся разные числа, и " +
                        "подделать совпадение математически невозможно.\n\n" +
                        "Главное условие: сверять код надо НЕ через нашу же " +
                        "переписку. Если атакующий уже встал посередине, он " +
                        "подделает пересылку цифр в чате и покажет вам «правильные» " +
                        "числа. Сверяйтесь вживую, по голосу через обычный звонок " +
                        "или через другой мессенджер.",
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(14.dp))

                // ── Сам код ───────────────────────────────────────────
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = code.ifBlank { "-" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ── Как сверять ──────────────────────────────────────
                Text(
                    text = "Как сверить",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "1. Откройте этот же экран у $partnerName на его телефоне.\n" +
                        "2. Сравните 60 цифр - у вас должны быть одинаковые.\n" +
                        "3. Если совпадают - нажмите «Подтверждаю».\n" +
                        "4. Если отличаются - НЕ обсуждайте чувствительное, " +
                        "возможно кто-то посередине.",
                    fontSize = 13.sp,
                )

                if (nowVerified) {
                    Spacer(Modifier.height(14.dp))
                    VerifiedBadge()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!nowVerified) {
                    KeyChangeTracker.markVerified(context, code)
                    nowVerified = true
                }
                onDismiss()
            }) {
                Text(if (nowVerified) "Закрыть" else "Подтверждаю")
            }
        },
        dismissButton = if (!nowVerified) {
            {
                TextButton(onClick = onDismiss) { Text("Позже") }
            }
        } else null,
    )
}

/**
 * Карточка-бейдж «код подтверждён ранее»: зелёная иконка-чекмарк, акцентный
 * фон. Используется в SafetyCodeDialog когда юзер уже сверил этот код вживую.
 */
@Composable
private fun VerifiedBadge() {
    val accent = Color(0xFF2E7D32) // material green 800
    val accentBg = Color(0xFF2E7D32).copy(alpha = 0.10f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accentBg,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Код подтверждён",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = accent,
                )
                Text(
                    text = "Вы уже сверили этот код с собеседником",
                    fontSize = 12.sp,
                    color = accent.copy(alpha = 0.85f),
                )
            }
        }
    }
}
