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

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.StoredChat

/**
 * Drawer content listing stored chats for the current task, with actions to create a new chat,
 * switch between chats, and delete a chat.
 */
@Composable
fun ChatListDrawerContent(
  chats: List<StoredChat>,
  activeChatId: String,
  onNewChatClicked: () -> Unit,
  onChatClicked: (StoredChat) -> Unit,
  onDeleteChatClicked: (StoredChat) -> Unit,
) {
  ModalDrawerSheet {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
      Text(
        stringResource(R.string.chat_list_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
      )

      // New chat.
      NavigationDrawerItem(
        icon = {
          Icon(
            Icons.Rounded.Add,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
          )
        },
        label = { Text(stringResource(R.string.chat_list_new_chat)) },
        selected = false,
        onClick = onNewChatClicked,
      )

      LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(chats, key = { it.id }) { chat ->
          NavigationDrawerItem(
            icon = {
              Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
            },
            label = {
              Column {
                Text(
                  chat.title.ifEmpty { stringResource(R.string.chat_list_untitled_chat) },
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                  DateUtils.getRelativeTimeSpanString(chat.updatedAtMs).toString(),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            badge = {
              IconButton(onClick = { onDeleteChatClicked(chat) }) {
                Icon(
                  Icons.Rounded.DeleteOutline,
                  contentDescription = stringResource(R.string.cd_delete_chat_icon),
                  modifier = Modifier.size(20.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            selected = chat.id == activeChatId,
            onClick = { onChatClicked(chat) },
          )
        }
      }
    }
  }
}
