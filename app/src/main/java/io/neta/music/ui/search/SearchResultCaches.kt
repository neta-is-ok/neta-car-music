package io.neta.music.ui.search

import io.neta.music.aggregate.CanonicalCollection
import io.neta.music.aggregate.CanonicalTrack
import io.neta.music.aggregate.TrackSource
import io.neta.music.ui.components.distinctKeys

/** 聚合单曲结果展开后的一行“其他版本”。 */
internal data class AlternativeRow(val track: CanonicalTrack, val source: TrackSource)

/**
 * 按聚合结果列表缓存展示所需的派生数据：去掉最佳匹配、生成去重 key、展开其他版本。
 * 只依赖结果列表本身，输入、焦点和播放状态变化不会触发重建。
 */
internal class SongResultsCache(
    val groups: List<CanonicalTrack>,
    val rows: List<CanonicalTrack>,
    val rowKeys: List<String>,
    val alternatives: List<AlternativeRow>,
    val alternativeKeys: List<String>,
)

internal fun buildSongResultsCache(groups: List<CanonicalTrack>): SongResultsCache {
    val rows = groups.drop(1)
    val alternatives = groups
        .flatMap { track -> track.alternatives.map { AlternativeRow(track, it) } }
        .distinctBy { it.source.song.key }
    return SongResultsCache(
        groups = groups,
        rows = rows,
        rowKeys = distinctKeys(rows) { it.id },
        alternatives = alternatives,
        alternativeKeys = alternatives.map { "alt:${it.source.song.key}" },
    )
}

internal fun collectionRowKeys(items: List<CanonicalCollection>): List<String> = distinctKeys(items) { it.id }
