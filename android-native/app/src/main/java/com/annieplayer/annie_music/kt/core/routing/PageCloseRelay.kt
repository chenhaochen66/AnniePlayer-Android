package com.annieplayer.annie_music.kt.core.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * 栈内页面的「优雅关闭」中继。
 *
 * ## 解决的问题
 * 返回键的**裁决**已经收口到 `AppShell` 一处（只有最该吃返回的是 `BackTarget.PAGE` 才弹栈）。
 * 但「弹栈」本身有两种：**带动画出栈**与**直接移除**。
 * 页面自己的关闭入口（顶栏返回箭头）走的是 `requestClose()` —— 先播 150ms 滑出、再 popRoute；
 * 而 `AppShell` 只能调 `popRoute()`，页面会「啪」地消失。
 *
 * 所以页面把自己的 `requestClose` 注册进来，`AppShell` 的返回处理器优先请求它：
 * - 注册了 → 播滑出动画，动画结束后页面自己 popRoute；
 * - 没注册（还没挂载 / 不是可动画页面）→ 退化为直接 `popRoute()`。
 *
 * ## 为什么是「有序列表」而不是单槽位
 * `pageStack` 按「栈底 → 栈顶」渲染，挂载顺序就是栈序，最后注册的就是栈顶。
 *
 * 早期实现只存一个槽位，栈顶出栈时把槽位清成 `null`，于是**下一个要返回的父页找不到自己的
 * 注册者**，退化到 `popRoute()` —— 表现就是「第一次返回有滑出动画，从子页退回父页再按返回，
 * 父页瞬间消失、没有动画」。
 *
 * 改成列表后，栈顶出栈只是把它自己从列表里移除，父页的注册者仍在列表尾部，
 * 下一次返回照样能播动画。层级可以任意深，不依赖「只有两层」的假设。
 */
object PageCloseRelay {

    /**
     * 按挂载顺序（= 栈序）排列的关闭者。
     *
     * 之所以用**引用相等**（`===`）而不是 equals：每个页面在 `DisposableEffect(Unit)` 里
     * 创建一个自己的闭包实例，`register` / `unregister` 拿的是同一个实例，
     * 引用比较才能在「同一个页面多次注册」时去重、在卸载时精确移除自己。
     */
    private val closers = ArrayList<() -> Unit>()

    /** 页面挂载时注册自己的 requestClose（列表尾部 = 栈顶）。 */
    fun register(close: () -> Unit) {
        if (closers.none { it === close }) closers += close
    }

    /** 页面卸载时撤销；只移除自己那一项，不影响其它层。 */
    fun unregister(close: () -> Unit) {
        closers.removeAll { it === close }
    }

    /**
     * 请求关闭栈顶页面。
     *
     * @return true = 已转交给页面（它会带动画出栈）；false = 没有注册者，调用方自行 popRoute
     */
    fun requestCloseTop(): Boolean {
        val target = closers.lastOrNull() ?: return false
        target()
        return true
    }
}

/**
 * 让一个栈内页面的 [requestClose] 参与返回裁决。
 *
 * 用法：页面里定义完 `requestClose`（先滑出、再 onClose）后调一行即可 ——
 * 不需要知道自己在栈里的位置，也不需要知道有没有更高层浮层。
 *
 * **必须只注册一次**（`DisposableEffect(Unit)` + `rememberUpdatedState`）：
 * `requestClose` 通常是每次重组都会重新创建的局部函数 / lambda，如果把它当
 * `DisposableEffect` 的 key，每次重组都会「撤销 + 重新注册」，在列表里表现为这个页面
 * 被挪到尾部 —— 一个被压在下面的页面只要重组一次，就会抢走「栈顶」的身份，
 * 返回时关错页面。用 `rememberUpdatedState` 保住最新实现，注册身份保持稳定。
 */
@Composable
fun RememberPageClose(requestClose: () -> Unit) {
    val latest by rememberUpdatedState(requestClose)
    DisposableEffect(Unit) {
        val closer: () -> Unit = { latest() }
        PageCloseRelay.register(closer)
        onDispose { PageCloseRelay.unregister(closer) }
    }
}
