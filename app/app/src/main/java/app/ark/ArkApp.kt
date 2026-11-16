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

/** 全局单例：模型只加载一份，切换页面、旋转屏幕都不会重新加载。 */
class ArkApp : Application() {
    lateinit var prefs: SharedPreferences
    lateinit var storage: Storage
    lateinit var packs: PackRepository
    lateinit var qa: QaStore
    lateinit var llm: LlmManager
    lateinit var embedder: Embedder
    /** 应用级作用域：模型空闲释放计时不能跟着 Activity / ViewModel 一起被取消 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
