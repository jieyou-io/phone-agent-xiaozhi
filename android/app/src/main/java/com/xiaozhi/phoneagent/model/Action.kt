package com.xiaozhi.phoneagent.model

sealed class Action {
    data class Finish(val message: String) : Action()

    sealed class Do : Action() {
        data class Tap(val x: Int, val y: Int) : Do()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : Do()
        data class Type(val text: String) : Do()
        data class Launch(val app: String) : Do()
        object Back : Do()
        object Home : Do()
        data class Wait(val seconds: Float) : Do()
        data class LongPress(val x: Int, val y: Int) : Do()
        data class DoubleTap(val x: Int, val y: Int) : Do()
    }
}
