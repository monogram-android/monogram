package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.monogram.presentation.core.ui.shimmerBackground

@Composable
fun ChatListShimmer(itemCount: Int = 10) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(itemCount) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .shimmerBackground(CircleShape)
                )

                Spacer(Modifier.size(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(14.dp)
                            .shimmerBackground(RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.height(9.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(12.dp)
                            .shimmerBackground(RoundedCornerShape(6.dp))
                    )
                }

                Spacer(Modifier.size(10.dp))

                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(width = 30.dp, height = 10.dp)
                        .shimmerBackground(RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

