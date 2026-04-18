/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.proto.StoredChat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ChatListDrawerContent(
  chats: List<StoredChat>,
  activeChatId: String?,
  onNewChat: () -> Unit,
  onSwitchChat: (StoredChat) -> Unit,
  onDeleteChat: (StoredChat) -> Unit,
) {
  val dateFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
    .withZone(ZoneId.systemDefault())
    .withLocale(Locale.getDefault())

  ModalDrawerSheet(modifier = Modifier.width(300.dp).fillMaxHeight()) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "Chats",
          style = MaterialTheme.typography.titleLarge,
        )
        TextButton(onClick = onNewChat) {
          Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
          Text("New Chat")
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

      if (chats.isEmpty()) {
        Text(
          "No saved chats yet",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 16.dp),
        )
      } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          items(chats, key = { it.id }) { chat ->
            val isActive = chat.id == activeChatId
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onSwitchChat(chat) }
                .padding(vertical = 10.dp, horizontal = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  chat.title.ifBlank { "Untitled" },
                  style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                  ),
                  color = if (isActive) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  dateFormatter.format(Instant.ofEpochMilli(chat.updatedAtMs)),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              IconButton(onClick = { onDeleteChat(chat) }) {
                Icon(
                  Icons.Rounded.Delete,
                  contentDescription = "Delete chat",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}
