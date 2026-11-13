package com.daily.quizbank.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.daily.quizbank.ui.screen.AiChatScreen
import com.daily.quizbank.ui.screen.BankDetailScreen
import com.daily.quizbank.ui.screen.BankListScreen
import com.daily.quizbank.ui.screen.ImportScreen
import com.daily.quizbank.ui.screen.SettingsScreen
import com.daily.quizbank.ui.screen.UsageScreen

object Routes {
    const val BANKS = "banks"
    const val BANK_DETAIL = "bank/{bankId}"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    const val CHAT = "chat"
    const val USAGE = "usage"

    fun bankDetail(bankId: Long) = "bank/" + bankId
}

@Composable
fun AppNav(
    overlayGranted: Boolean,
    floatingRunning: Boolean,
    onToggleFloating: (Boolean) -> Unit,
    initialRoute: String,
    incomingFileUri: Uri? = null,
    onIncomingFileHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val viewModel: QuizBankViewModel = viewModel()

    // 从微信「用其他应用打开」送进来的文件：直接开始解析并跳到导入预览页
    LaunchedEffect(incomingFileUri) {
        val uri = incomingFileUri ?: return@LaunchedEffect
        viewModel.startImport(uri)
        navController.navigate(Routes.IMPORT) { launchSingleTop = true }
        onIncomingFileHandled()
    }

    NavHost(navController = navController, startDestination = initialRoute) {

        composable(Routes.BANKS) {
            BankListScreen(
                viewModel = viewModel,
                overlayGranted = overlayGranted,
                floatingRunning = floatingRunning,
                onToggleFloating = onToggleFloating,
                onOpenBank = { id -> navController.navigate(Routes.bankDetail(id)) },
                onCreateBank = { id -> navController.navigate(Routes.bankDetail(id)) },
                onOpenImport = { navController.navigate(Routes.IMPORT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenChat = { navController.navigate(Routes.CHAT) },
                onOpenUsage = { navController.navigate(Routes.USAGE) },
            )
        }

        composable(
            route = Routes.BANK_DETAIL,
            arguments = listOf(navArgument("bankId") { type = NavType.LongType }),
        ) { entry ->
            val bankId = entry.arguments?.getLong("bankId") ?: 0L
            BankDetailScreen(
                viewModel = viewModel,
                bankId = bankId,
                onBack = { navController.popBackStack() },
                onOpenImport = { navController.navigate(Routes.IMPORT) },
            )
        }

        composable(Routes.IMPORT) {
            ImportScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onFinished = { bankId ->
                    viewModel.resetImport()
                    navController.navigate(Routes.bankDetail(bankId)) {
                        popUpTo(Routes.BANKS)
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CHAT) {
            AiChatScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.USAGE) {
            UsageScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
