package io.neta.music.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.neta.music.data.ProviderInfo
import io.neta.music.data.Song

/**
 * 滑动操作「加到歌单」弹的面板：复用加歌单的选取列表，选了就加、新建直接带着这首。
 * 上游的勾选式面板（在几个歌单间勾来勾去、可撤销）依赖歌单编辑的并发改造，这里用简单版。
 */
@androidx.compose.runtime.Composable
fun AddToPlaylistSheet(song: Song, actions: MusicActions, onDismiss: () -> Unit, providers: List<ProviderInfo> = emptyList()) {
    val playlists by actions.localPlaylists.collectAsState()
    PlaylistPickerSheet(
        playlists = playlists,
        onPick = { id ->
            actions.addSongToPlaylist(id, song)
            onDismiss()
        },
        onCreate = { name ->
            actions.createPlaylistWithSong(name, song)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
