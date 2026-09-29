package com.zdt.stage

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.zdt.stage.ui.MainWindow
import com.zdt.stage.ui.theme.ZdtStageTheme
import com.zdt.stage.viewmodel.StageViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: StageViewModel by viewModels()

    // 导出配置 SAF
    private val exportConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.let { outStream ->
                    viewModel.exportConfig(outStream)
                    Toast.makeText(this, "正在导出配置...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "打开写入流失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 导入配置 SAF
    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.let { inStream ->
                    viewModel.importConfig(inStream)
                    Toast.makeText(this, "正在导入配置...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "读取配置文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ZdtStageTheme {
                MainWindow(
                    viewModel = viewModel,
                    onImportClick = {
                        importConfigLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    onExportClick = {
                        exportConfigLauncher.launch("zdt_config.json")
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 【安全核心保证】：退出 Activity 时严禁主动向电机下发 disable 指令！
        // 保持垂直 Z 轴锁相自锁力矩，避免重力自由坠落砸伤机械设备。
    }
}
