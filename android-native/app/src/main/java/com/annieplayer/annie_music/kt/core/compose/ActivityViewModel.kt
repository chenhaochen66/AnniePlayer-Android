package com.annieplayer.annie_music.kt.core.compose

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner

/** 从 CompositionLocal 的 Context 向上找到 [ViewModelStoreOwner]（Activity）。 */
private tailrec fun Context?.findViewModelStoreOwner(): ViewModelStoreOwner? = when (this) {
    null -> null
    is ViewModelStoreOwner -> this
    is ContextWrapper -> baseContext.findViewModelStoreOwner()
    else -> null
}

/**
 * 以 Activity 为作用域获取 ViewModel（等价 `androidx.lifecycle.viewmodel.compose.viewModel()`）。
 *
 * 单 Activity 架构的宿主屏用它，可让状态（播放队列 / UI / 选择等）在**屏幕旋转 / Activity
 * 重建**后存活；而 `remember { XxxViewModel() }` 会随组合销毁而丢状态。这里不直接依赖
 * `lifecycle-viewmodel-compose`（本工程离线构建缓存中不存在该 artifact）。
 */
@Composable
internal inline fun <reified VM : ViewModel> activityViewModel(): VM {
    val owner = LocalContext.current.findViewModelStoreOwner()
        ?: error("未找到 ViewModelStoreOwner，activityViewModel() 需在 Activity 宿主下使用")
    return remember(owner) { ViewModelProvider(owner).get(VM::class.java) }
}
