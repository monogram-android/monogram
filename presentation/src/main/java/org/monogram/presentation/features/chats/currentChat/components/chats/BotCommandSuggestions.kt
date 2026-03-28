package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.BotCommandModel
import org.monogram.presentation.core.ui.ItemPosition

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotCommandSuggestions(
    commands: List<BotCommandModel>,
    onCommandClick: (String) -> Unit,
    modifier: Modifier
) {
    AnimatedVisibility(
        visible = commands.isNotEmpty(),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(
                items = commands,
                key = { index, command -> "cmd_suggest_${index}_${command.command}" }
            ) { index, command ->
                val position = when {
                    commands.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == commands.size - 1 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }
                BotCommandItem(
                    command = command,
                    position = position,
                    onClick = { onCommandClick(command.command) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}