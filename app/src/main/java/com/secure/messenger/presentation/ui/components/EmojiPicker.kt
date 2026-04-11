package com.secure.messenger.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Простая категория эмоджи для пикера.
 */
private data class EmojiCategory(val title: String, val icon: String, val emojis: List<String>)

private val EMOJI_CATEGORIES = listOf(
    EmojiCategory(
        title = "Смайлы",
        icon = "😀",
        emojis = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
            "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
            "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩",
            "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣",
            "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬",
            "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗",
            "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯",
            "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐",
            "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠",
        ),
    ),
    EmojiCategory(
        title = "Жесты",
        icon = "👍",
        emojis = listOf(
            "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉",
            "👆", "🖕", "👇", "☝️", "👋", "🤚", "🖐", "✋", "🖖", "👏",
            "🙌", "🤲", "🙏", "🤝", "💪", "🦾", "✊", "👊", "🤛", "🤜",
        ),
    ),
    EmojiCategory(
        title = "Сердца",
        icon = "❤️",
        emojis = listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
            "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "♥️",
        ),
    ),
    EmojiCategory(
        title = "Животные",
        icon = "🐶",
        emojis = listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
            "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐔", "🐧",
            "🐦", "🐤", "🦆", "🦅", "🦉", "🐺", "🐗", "🐴", "🦄", "🐝",
            "🐛", "🦋", "🐌", "🐞", "🐢", "🐍", "🦎", "🦂", "🦀", "🐙",
        ),
    ),
    EmojiCategory(
        title = "Еда",
        icon = "🍔",
        emojis = listOf(
            "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈",
            "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑", "🥦",
            "🥬", "🥒", "🌶", "🫑", "🌽", "🥕", "🧄", "🧅", "🥔", "🍠",
            "🍞", "🥐", "🥖", "🫓", "🥨", "🥞", "🧇", "🧀", "🍖", "🍗",
            "🥩", "🥓", "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🫔",
            "🥗", "🥘", "🍜", "🍝", "🍣", "🍱", "🍤", "🍩", "🍪", "🎂",
            "🍰", "🧁", "🍫", "🍬", "🍭", "☕", "🍵", "🥤", "🧋", "🍺",
        ),
    ),
    EmojiCategory(
        title = "Объекты",
        icon = "💡",
        emojis = listOf(
            "⌚", "📱", "💻", "⌨️", "🖥", "🖨", "🖱", "💾", "💿", "📷",
            "📹", "🎥", "📞", "☎️", "📟", "📺", "📻", "🎙", "🎚", "🎛",
            "⏱", "⏲", "⏰", "🕰", "⌛", "📡", "🔋", "🔌", "💡", "🔦",
            "🕯", "🪔", "🧯", "🛢", "💸", "💵", "💴", "💶", "💷", "💰",
            "💳", "💎", "⚖️", "🪜", "🧰", "🔧", "🔨", "⚒", "🛠", "⛏",
        ),
    ),
    EmojiCategory(
        title = "Символы",
        icon = "✅",
        emojis = listOf(
            "✅", "❌", "⭕", "🚫", "❗", "❓", "❕", "❔", "‼️", "⁉️",
            "💯", "🔥", "✨", "⚡", "💫", "💥", "💢", "💦", "💨", "🎉",
            "🎊", "🎈", "🎂", "🎁", "🏆", "🏅", "🥇", "🥈", "🥉", "⚽",
            "🌟", "⭐", "🌠", "☀️", "🌤", "⛅", "🌧", "⛈", "🌩", "🌨",
        ),
    ),
)

/**
 * Панель выбора эмоджи. Открывается под полем ввода с slide-анимацией.
 *
 * @param onEmojiSelected Колбэк при тапе на эмоджи — добавляет его в текст
 */
@Composable
fun EmojiPicker(
    onEmojiSelected: (String) -> Unit,
) {
    var selectedCategory by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Тот же цвет что у MessageInputBar — иначе в тёмной теме видна
            // тёмная полоса-разрыв между инпутом и эмоджи-панелью
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        // Категории сверху — без верхнего отступа, чтобы не было щели
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            EMOJI_CATEGORIES.forEachIndexed { index, category ->
                val isSelected = selectedCategory == index
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable { selectedCategory = index },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = category.icon,
                        fontSize = 22.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        // Сетка эмоджи текущей категории (без divider — создавал визуальный разрыв)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 44.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(EMOJI_CATEGORIES[selectedCategory].emojis) { emoji ->
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onEmojiSelected(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = emoji, fontSize = 26.sp)
                }
            }
        }
    }
}
