package app.ark

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ark.ui.AppViewModel
import app.ark.ui.ArkRoot
import app.ark.ui.ArkTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val s = vm.settings.collectAsStateWithLifecycle().value
            ArkTheme(s.theme) { ArkRoot(vm) }
        }
        debugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugIntent(intent)
    }

    /**
     * 仅调试版：用 adb 自动提问，做端侧回归测试（adb 不能直接输入中文）。
     *   adb shell am start -n app.ark.offline/app.ark.MainActivity --es ark_ask_b64 <base64 问题>
     *   ... --es ark_search_b64 <base64>   只检索（资料页搜索）
     *   ... --es ark_batch <文件名> [--ei ark_max <最长 token>]   批量生成：读 files/eval/<文件名>，写 files/eval/out_<文件名>
     */
    private fun debugIntent(i: Intent?) {
        if (i == null || applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        fun arg(k: String) = i.getStringExtra(k)?.let { String(Base64.decode(it, Base64.DEFAULT)) }
        i.getStringExtra("ark_model")?.let { m -> vm.update { it.copy(model = m) } } // --es ark_model <models/ 下的文件名>
        i.removeExtra("ark_model")
        i.getStringExtra("ark_spec")?.let { getSharedPreferences("ark", MODE_PRIVATE).edit().putString("debug_spec", it).commit() }
        i.removeExtra("ark_spec")
        i.getStringExtra("ark_act")?.let { getSharedPreferences("ark", MODE_PRIVATE).edit().putString("debug_act", it).commit() }
        i.removeExtra("ark_act")
        arg("ark_ask_b64")?.let { vm.ask(it) }
        arg("ark_search_b64")?.let { vm.searchLibrary(it) }
        i.getStringExtra("ark_batch")?.let { n ->
            val dir = java.io.File(getExternalFilesDir(null), "eval")
            vm.debugBatch(java.io.File(dir, n), java.io.File(dir, "out_$n"), i.getIntExtra("ark_max", 0).takeIf { it > 0 })
        }
        i.removeExtra("ark_ask_b64"); i.removeExtra("ark_search_b64"); i.removeExtra("ark_batch")
    }

    override fun onResume() {
        super.onResume()
        vm.refreshFiles() // 用户可能用数据线往目录里拷了文件
    }
}
