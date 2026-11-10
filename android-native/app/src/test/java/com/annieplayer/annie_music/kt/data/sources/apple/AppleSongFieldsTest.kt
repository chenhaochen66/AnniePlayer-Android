package com.annieplayer.annie_music.kt.data.sources.apple

import com.annieplayer.annie_music.kt.data.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple 歌手曲目接口（`/artists/{id}/view/top-songs`）的字段投影与映射。
 *
 * 守两件事：
 * 1. `fields[songs]=` 投影（单页 125 KB → 79.6 KB）**必须覆盖 [appleSongOf] 读的每一个属性**。
 *    漏一个不会报错，只会让那个字段静默变空 —— 例如漏 `url` 会让专辑 id 变成 null，
 *    漏 `audioTraits` 会让所有歌退化成 AAC。
 * 2. 投影后仍能完整映射成 `Song`，且**不按分隔符硬拆** `artistName`（`Lucas & Steve` 是乐队名）。
 *
 * 夹具是 2026-11 实测响应里切出来的（Taylor Swift《Patient Zero》），只保留投影后的属性。
 */
class AppleSongFieldsTest {

    /** `appleSongOf` 会读的属性；`fields[songs]` 少一个就是静默丢字段。 */
    private val requiredFields = listOf(
        "name",
        "artistName",
        "albumArtistName",
        "albumName",
        "artwork",
        "durationInMillis",
        "trackNumber",
        "discNumber",
        "releaseDate",
        "composerName",
        "genreNames",
        "isrc",
        "url",
        "audioTraits",
    )

    @Test
    fun `投影覆盖 appleSongOf 读取的每个属性`() {
        val projected = AppleSongFields.split(",").toSet()
        val missing = requiredFields.filterNot { it in projected }
        assertTrue("fields[songs] 缺了这些属性：$missing", missing.isEmpty())
    }

    @Test
    fun `投影后的响应能完整映射`() {
        val raw = mapOf(
            "id" to "6814997425",
            "type" to "songs",
            "attributes" to mapOf(
                "name" to "Patient Zero",
                "artistName" to "Taylor Swift",
                "albumArtistName" to "Taylor Swift",
                "albumName" to "The Life of a Showgirl: The Encore",
                "artwork" to mapOf(
                    "url" to "https://is1-ssl.mzstatic.com/thumb/x.rgb.jpg/{w}x{h}bb.jpg",
                ),
                "durationInMillis" to 225868,
                "trackNumber" to 1,
                "discNumber" to 2,
                "releaseDate" to "2026-09-24",
                "composerName" to "Taylor Swift, Max Martin & Shellback",
                "genreNames" to listOf("Pop", "Music"),
                "isrc" to "USUG12602391",
                "url" to "https://music.apple.com/us/album/patient-zero/6814997249?i=6814997425",
                "audioTraits" to listOf("atmos", "lossless", "lossy-stereo", "spatial"),
            ),
        )
        val song = appleSongOf(raw)!!
        assertEquals("apple:6814997425", song.id)
        assertEquals("Patient Zero", song.title)
        assertEquals("Taylor Swift", song.artist)
        assertEquals("The Life of a Showgirl: The Encore", song.album)
        // 专辑 id 只能从 url 里抠 —— 投影漏了 `url` 这里就变 null
        assertEquals("6814997249", song.albumId)
        assertEquals(225, song.duration)
        assertEquals("1", song.trackNumber)
        assertEquals("2", song.discNumber)
        assertEquals("2026-09-24", song.releaseDate)
        assertEquals("Taylor Swift, Max Martin & Shellback", song.composer)
        assertEquals("Pop / Music", song.genre)
        assertEquals("USUG12602391", song.isrc)
        assertEquals(
            "https://is1-ssl.mzstatic.com/thumb/x.rgb.jpg/600x600bb.jpg",
            song.cover,
        )
        // audioTraits 决定音质档位：漏了它整页都会退化成 AAC
        assertEquals(listOf(Quality.atmos, Quality.alac, Quality.aac256), song.qualities)
    }

    @Test
    fun `没有 relationships 时不硬拆 artistName`() {
        // top-songs 视图的投影不带 relationships.artists（除非显式 relate[songs]=artists，
        // 而那条只给 id 不给 name）—— 此时必须留空，不能把 `A, B & C` 拆成三个人
        val song = appleSongOf(
            mapOf(
                "id" to "1",
                "attributes" to mapOf("name" to "X", "artistName" to "Lucas & Steve"),
            ),
        )!!
        assertEquals("Lucas & Steve", song.artist)
        assertEquals(emptyList<String>(), song.artistNames)
        assertEquals(emptyList<String>(), song.artistIds)
    }

    @Test
    fun `relationships 有名字时按下标配对`() {
        val song = appleSongOf(
            mapOf(
                "id" to "2",
                "attributes" to mapOf("name" to "Y", "artistName" to "Tyla, Gunna & Skillibeng"),
                "relationships" to mapOf(
                    "artists" to mapOf(
                        "data" to listOf(
                            mapOf("id" to "1", "attributes" to mapOf("name" to "Tyla")),
                            mapOf("id" to "2", "attributes" to mapOf("name" to "Gunna")),
                            // id 有、name 空 → 必须成对丢掉，不能只丢一边把后面的人错位
                            mapOf("id" to "3", "attributes" to mapOf("name" to "")),
                        ),
                    ),
                ),
            ),
        )!!
        assertEquals(listOf("Tyla", "Gunna"), song.artistNames)
        assertEquals(listOf("1", "2"), song.artistIds)
    }

    @Test
    fun `没有 url 时专辑 id 退回 relationships`() {
        val song = appleSongOf(
            mapOf(
                "id" to "3",
                "attributes" to mapOf("name" to "Z"),
                "relationships" to mapOf(
                    "albums" to mapOf("data" to listOf(mapOf("id" to "999"))),
                ),
            ),
        )!!
        assertEquals("999", song.albumId)
    }

    @Test
    fun `缺 id 的条目被丢掉`() {
        assertNull(appleSongOf(mapOf("attributes" to mapOf("name" to "无 id"))))
    }
}
