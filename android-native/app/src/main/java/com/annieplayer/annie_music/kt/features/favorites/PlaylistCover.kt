package com.annieplayer.annie_music.kt.features.favorites

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** 自选封面的长边上限（与本地曲库抽封面的 512 一致；一张 512 的 JPEG 约 60~90 KB） */
private const val PlaylistCoverMaxPx = 512

/** 封面 JPEG 质量（88：肉眼看不出压缩，体积只有 100 的一半左右） */
private const val PlaylistCoverQuality = 88

/**
 * 把用户从系统相册 / 文件管理器选中的图片读成**压缩后的 JPEG 字节**。
 *
 * 为什么要复制字节而不是记住 `content://`：那个 Uri 的读权限只在本次进程、本次授权内有效，
 * 重启或换页面就打不开（封面变成空白）。而且用户要求「收藏的东西都进 SQLite」——
 * 图片也就跟着进库（`user_playlists.cover_blob`），不额外留文件。
 *
 * @return null = 读不出来（不是图片 / 文件已被删 / 解码失败），调用方按「没选封面」处理
 */
internal suspend fun readPlaylistCoverBytes(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            val src = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@runCatching null
            val longSide = maxOf(src.width, src.height)
            val scaled = if (longSide <= PlaylistCoverMaxPx || longSide == 0) {
                src
            } else {
                val ratio = PlaylistCoverMaxPx.toFloat() / longSide
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * ratio).toInt().coerceAtLeast(1),
                    (src.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            }
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, PlaylistCoverQuality, out)
                if (scaled !== src) scaled.recycle()
                src.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }
