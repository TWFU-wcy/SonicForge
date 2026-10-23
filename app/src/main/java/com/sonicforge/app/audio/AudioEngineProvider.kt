package com.sonicforge.app.audio

import android.content.Context

/**
 * AudioEngine 单例提供者。
 *
 * 由于 AudioEngine 构造需要 Context，而 UI 与守护进程客户端都要访问同一个实例，
 * 所以用一个全局单例持有它。
 */
object AudioEngineProvider {

    @Volatile
    private var instance: AudioEngine? = null

    /** 获取全局 AudioEngine 实例（懒初始化）。 */
    @JvmStatic
    fun get(context: Context): AudioEngine {
        return instance ?: synchronized(this) {
            instance ?: AudioEngine(context.applicationContext).also { instance = it }
        }
    }

    /** 注入预构造实例（仅用于测试或进程内替换）。 */
    @JvmStatic
    fun set(engine: AudioEngine) {
        instance = engine
    }
}
