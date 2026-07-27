package com.ktakjm.fingerlock.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File

/**
 * 侵入者セルフィー(issue #3)の保存先とデコード。
 *
 * 保存先はアプリ内部ストレージ `filesDir/intruder/<epochMillis>.jpg` 固定。外部ストレージには
 * 書き出さないので保存に権限は要らず、アンインストールで消える。
 */
object IntruderPhotoStore {

    private const val DIR_NAME = "intruder"
    private const val MAX_PHOTOS = 20

    fun newPhotoFile(context: Context, timestamp: Long): File =
        File(photoDir(context), "$timestamp.jpg")

    /** 上限を超えた分を古い順に削除する */
    fun trim(context: Context) {
        val files = photoDir(context).listFiles()?.takeIf { it.size > MAX_PHOTOS } ?: return
        files.sortedBy { it.nameWithoutExtension.toLongOrNull() ?: 0L }
            .dropLast(MAX_PHOTOS)
            .forEach { it.delete() }
    }

    /**
     * 長辺が [maxPx] 程度に収まるようサブサンプルして読み込む。
     * EXIF の回転を反映させるため BitmapFactory ではなく ImageDecoder を使う。
     * 上限超過で削除済みのパスや壊れたファイルは null を返す。
     */
    fun decode(path: String, maxPx: Int): Bitmap? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                // 通知に載せる/Composeで描画するのでハードウェアビットマップは使えない
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = maxOf(info.size.width, info.size.height) / maxPx
                if (scale > 1) decoder.setTargetSampleSize(Integer.highestOneBit(scale))
            }
        }.getOrNull()
    }

    private fun photoDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }
}
