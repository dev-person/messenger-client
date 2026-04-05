package com.secure.messenger.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Компактная шапка.
 *
 * Ключевое: statusBarsPadding() вешаем на Column, а не на Row с height().
 * Если повесить statusBarsPadding() на Row с height(N.dp), то N dp будут
 * съедены отступом статусбара — контенту остаётся (N - statusBar) dp.
 *
 * Правильно: Column(statusBarsPadding) { Row(height = 28dp) }
 * Surface закрашивает область за статусбаром в primary-цвет.
 */
@Composable
fun CompactTopBar(
    title: @Composable RowScope.() -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigationIcon()
                title()
                actions()
            }
        }
    }
}
