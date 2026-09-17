package org.monogram.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.R

private val BubbleCorner = 18.dp
private val TailCorner = 2.dp

@Composable
fun MessageListSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 6,
) {
    val description = stringResource(R.string.status_connecting)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(itemCount) { index ->
            val outgoing = index % 3 == 0
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                MonogramPlaceholder(
                    modifier = Modifier
                        .width(if (outgoing) 220.dp else 260.dp)
                        .height(if (index % 2 == 0) 56.dp else 88.dp),
                    shape = if (outgoing) {
                        RoundedCornerShape(
                            topStart = BubbleCorner,
                            topEnd = BubbleCorner,
                            bottomStart = BubbleCorner,
                            bottomEnd = TailCorner,
                        )
                    } else {
                        RoundedCornerShape(
                            topStart = BubbleCorner,
                            topEnd = BubbleCorner,
                            bottomStart = TailCorner,
                            bottomEnd = BubbleCorner,
                        )
                    },
                )
            }
        }
    }
}
