/*
 * ArkApp.kt —— Application 子类，进程级单例的持有者。
 *
 * 位置：所有页面（AppViewModel、各 Service）都通过 `application as ArkApp` 取到
 * prefs / storage / packs / qa / llm / embedder；它依赖 data/、llm/ 两个包的类，自己不含业务逻辑。
 * 设计：大模型引擎加载一次要几秒到几十秒、占 GB 级内存，所以放在 Application 里只建一份，
 * Activity 重建（旋转、切页）不会重新加载。
 */
package app.ark

import android.app.Application
import android.content.SharedPreferences
import app.ark.data.PackRepository
import app.ark.data.QaStore
import app.ark.data.Storage
import app.ark.llm.Embedder
import app.ark.llm.LlmManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.maplibre.android.MapLibre
import java.io.File

/**
 * 全局单例：模型只加载一份，切换页面、旋转屏幕都不会重新加载。
 *
 * 所有 lateinit 字段在 [onCreate] 里一次性赋值，之后只读；在主线程之外读取也安全（onCreate 先于任何组件）。
 * 在 AndroidManifest 里通过 android:name 注册。
 */
class ArkApp : Application() {
    lateinit var prefs: SharedPreferences
    lateinit var storage: Storage
    lateinit var packs: PackRepository
    lateinit var qa: QaStore
    lateinit var llm: LlmManager
    lateinit var embedder: Embedder
    /** 应用级作用域：模型空闲释放计时不能跟着 Activity / ViewModel 一起被取消 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 模型“空闲一段时间后自动卸载”的倒计时任务（由 AppViewModel 设置/取消），可能被多个线程访问所以 @Volatile */
    @Volatile var idleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ark", MODE_PRIVATE)
        storage = Storage(this)
        packs = PackRepository(storage, prefs)
        qa = QaStore(File(filesDir, "ark.db"))
        llm = LlmManager(this, prefs)
        embedder = Embedder(this)
        MapLibre.getInstance(this) // 地图引擎初始化；没有在线瓦片服务，也不需要 API key
    }
}
