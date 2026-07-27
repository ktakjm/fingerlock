package com.ktakjm.fingerlock.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.annotation.MainThread
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 連続失敗時にインカメラでプレビューなしの静止画を1枚撮る(issue #3)。
 *
 * カメラを開いてバインドするまでに0.5〜1秒かかるので、閾値到達の1回手前で [warmUp] しておき、
 * [capture] では takePicture だけを走らせる。撮影は常にベストエフォートで、権限剥奪・カメラ使用中・
 * タイムアウトはすべて null を返して握りつぶす。
 *
 * 撮影中に呼び出し元Activityが終了するとカメラが切断されるため、Activityのライフサイクルではなく
 * 自前の [CaptureLifecycleOwner] にバインドし、[release] で明示的に解放する。
 */
object IntruderCamera {

    private const val CAPTURE_TIMEOUT_MS = 5_000L

    // bindToLifecycle と LifecycleRegistry はメインスレッド専用
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var session: Deferred<Session?>? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** カメラを開いてバインドしておく。既にウォームアップ済みなら何もしない */
    @MainThread
    fun warmUp(context: Context) {
        if (session != null || !hasPermission(context)) return
        val app = context.applicationContext
        session = scope.async { open(app) }
    }

    /** 1枚撮影して保存先の絶対パスを返す。撮影できなければ null */
    suspend fun capture(context: Context, timestamp: Long): String? {
        val app = context.applicationContext
        if (!hasPermission(app)) return null
        val imageCapture = withContext(Dispatchers.Main.immediate) {
            warmUp(app)
            session
        }?.await()?.imageCapture ?: return null

        val file = withContext(Dispatchers.IO) {
            IntruderPhotoStore.newPhotoFile(app, timestamp)
        }
        val saved = runCatching {
            withTimeout(CAPTURE_TIMEOUT_MS) { takePicture(imageCapture, file) }
        }.isSuccess
        return withContext(Dispatchers.IO) {
            if (saved) {
                IntruderPhotoStore.trim(app)
                file.absolutePath
            } else {
                file.delete()
                null
            }
        }
    }

    /** カメラを解放する。ロックセッションの終了時に必ず呼ぶ(他アプリのカメラを塞がないため) */
    @MainThread
    fun release() {
        val pending = session ?: return
        session = null
        // バインド途中で解放を取りこぼさないよう、完了を待ってからアンバインドする
        scope.launch {
            pending.await()?.let {
                it.provider.unbind(it.imageCapture)
                it.owner.destroy()
            }
        }
    }

    private class Session(
        val provider: ProcessCameraProvider,
        val owner: CaptureLifecycleOwner,
        val imageCapture: ImageCapture,
    )

    private suspend fun open(context: Context): Session? = runCatching {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // 顔が判別できれば十分。ファイルサイズと撮影時間を抑える
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
            )
            .build()
        val owner = CaptureLifecycleOwner()
        owner.start()
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)
        Session(provider, owner, imageCapture)
    }.getOrNull()

    private suspend fun takePicture(imageCapture: ImageCapture, file: File) {
        suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                Dispatchers.IO.asExecutor(),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        continuation.resume(Unit)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addListener(
                {
                    runCatching { get() }
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                },
                Executor { it.run() },
            )
            continuation.invokeOnCancellation { cancel(false) }
        }
}

/** Activityのライフサイクルから切り離してカメラをバインドするための最小のLifecycleOwner */
private class CaptureLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
