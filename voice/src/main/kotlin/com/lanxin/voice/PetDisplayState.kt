package com.lanxin.voice

/**
 * Live2D / 桌宠显示就绪状态（P5 占位）。
 * 真 WebView+moc3 后续迁移；此处仅可观测状态，避免 silent fail。
 */
sealed class PetDisplayState {
    data object Uninitialized : PetDisplayState()
    data class Placeholder(val reason: String = "no_live2d_yet") : PetDisplayState()
    data class Ready(val modelId: String, val source: String) : PetDisplayState()
    data class Failed(val detail: String) : PetDisplayState()

    val shortLabel: String
        get() = when (this) {
            is Uninitialized -> "未初始化"
            is Placeholder -> "占位:$reason"
            is Ready -> "READY($modelId/$source)"
            is Failed -> "FAILED:$detail"
        }
}

/** 最小占位实现：始终 Placeholder，不装 WebView。 */
class PlaceholderPetDisplay {
    var state: PetDisplayState = PetDisplayState.Placeholder("p5_skeleton")
        private set

    fun markReadyBuiltin(modelId: String = "Mao") {
        state = PetDisplayState.Ready(modelId = modelId, source = "builtin_sample_pending")
    }

    fun reset() {
        state = PetDisplayState.Placeholder("p5_skeleton")
    }
}
