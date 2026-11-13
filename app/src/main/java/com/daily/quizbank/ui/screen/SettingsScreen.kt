package com.daily.quizbank.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daily.quizbank.data.ai.ModelInfo
import com.daily.quizbank.data.ai.ModelProviders
import com.daily.quizbank.data.prefs.AppSettings
import com.daily.quizbank.data.prefs.Prompts
import com.daily.quizbank.data.prefs.Recognizer
import com.daily.quizbank.data.repo.QuestionRepository
import com.daily.quizbank.QuizBankApp
import com.daily.quizbank.ui.QuizBankViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: QuizBankViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settingsStore.flow.collectAsStateWithLifecycle(initialValue = AppSettings())

    // 文本框用本地状态 + 手动保存：直接双向绑定 DataStore 会在每次按键时异步回写，输入法下容易丢字
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    // 模型名**只有一格**（原来分文本模型/视觉模型两个框）。默认空白：
    // 没配过的人从空开始，先测地址和 Key，再从下拉里选；配过的人照旧显示他存的那一个。
    var modelField by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var answerPrompt by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    // 免费通道。和 DeepSeek 那三个字段一样，用本地状态 + 手动保存。
    var freeBaseUrl by remember { mutableStateOf("") }
    var freeApiKey by remember { mutableStateOf("") }
    var freeModel by remember { mutableStateOf("") }
    var showFreeKey by remember { mutableStateOf(false) }

    // 点了服务商预设之后的一句提示：只填字段、不落盘，得点「保存」才生效
    var providerHint by remember { mutableStateOf<String?>(null) }

    // 两段式连接：先「连接测试」验地址和 Key，测通了才放出可选模型和「连接」按钮。
    // 状态全放在这一屏，因为它只服务于这一栏；测试失败要能指出是**哪一格**错了。
    var testState by remember { mutableStateOf(ConnectState.IDLE) }
    var testError by remember { mutableStateOf<String?>(null) }
    var badUrl by remember { mutableStateOf(false) }
    var badKey by remember { mutableStateOf(false) }
    var blinkOn by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }

    // 闪红：给填错的那一格刷一层红底，闪三下
    val flashTint = if (blinkOn) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
    } else {
        Color.Transparent
    }

    // 「整理题库」是个可能要跑几十秒的批处理（上千道题），所以要有忙碌态和结果回报。
    val app = LocalContext.current.applicationContext as QuizBankApp
    val tidyScope = rememberCoroutineScope()
    val connectScope = rememberCoroutineScope()

    /** 让出错的那一格红底闪三下。 */
    fun flashFields() {
        connectScope.launch {
            repeat(3) {
                blinkOn = true
                delay(120)
                blinkOn = false
                delay(90)
            }
        }
    }

    /**
     * 「连接测试」：只拿**输入框里此刻的**地址和 Key 去列一次模型，不动任何设置。
     *
     * 没填或填错时不给一句笼统的「失败」，而是把能确定的那一格标红：
     * 空着 → 直接标红说出来；请求失败 → 地址和 Key 都可能错，两格一起标，并把接口回的错原文摆出来。
     */
    fun runConnectTest() {
        val url = baseUrl.trim()
        val key = apiKey.trim()
        badUrl = url.isBlank()
        badKey = key.isBlank()
        if (badUrl || badKey) {
            testState = ConnectState.FAIL
            models = emptyList()
            testError = when {
                badUrl && badKey -> "接口地址和 API Key 都还没填"
                badUrl -> "先填接口地址，例如 https://api.deepseek.com"
                else -> "先填 API Key（服务商控制台里复制一个）"
            }
            flashFields()
            return
        }
        testState = ConnectState.TESTING
        testError = null
        models = emptyList()
        connectScope.launch {
            val probe = viewModel.settingsStore.snapshot().copy(apiKey = key, baseUrl = url)
            runCatching { app.deepSeek.listModels(probe) }
                .onSuccess { list ->
                    models = list
                    testState = ConnectState.OK
                    testError = null
                }
                .onFailure { error ->
                    testState = ConnectState.FAIL
                    badUrl = true
                    badKey = true
                    testError = "地址或 Key 不对：" + (error.message ?: "请求失败").trim()
                    flashFields()
                }
        }
    }
    var tidying by remember { mutableStateOf(false) }
    var tidyResult by remember { mutableStateOf<String?>(null) }

    // 「找出相似题」：先把整个题库扫一遍（上千道要一秒左右），再把结果摊给用户逐组挑。
    var findingSimilar by remember { mutableStateOf(false) }
    var similarReport by remember { mutableStateOf<QuestionRepository.SimilarReport?>(null) }
    var similarMessage by remember { mutableStateOf<String?>(null) }
    var similarBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val current = viewModel.settingsStore.snapshot()
        apiKey = current.apiKey
        baseUrl = current.baseUrl
        // 「模型名」默认空白：还没配过 Key 的人从空开始（先测再选），配过的人显示他存的那个
        modelField = if (current.apiKey.isBlank() && current.chatModel == AppSettings.DEFAULT_MODEL) {
            ""
        } else {
            current.chatModel
        }
        systemPrompt = current.systemPrompt
        answerPrompt = current.answerPrompt
        freeBaseUrl = current.freeBaseUrl
        freeApiKey = current.freeApiKey
        freeModel = current.freeModel
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("模型接口（任意 OpenAI 兼容）") {
                    Text(
                        "本应用的 AI 请求走的是 OpenAI 协议，所以接哪家都行：选一个服务商，地址和模型名就填好了，" +
                            "再粘上你自己的 Key，点「保存」生效。地址和模型名随时可以手改。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 当前生效的是哪一档（按地址 + 模型名反推，不存 id：手改过也不会指错）
                    val activeId = ModelProviders.match(
                        baseUrl.ifBlank { settings.baseUrl },
                        modelField.ifBlank { settings.chatModel },
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ModelProviders.ALL.forEach { provider ->
                            FilterChip(
                                selected = activeId == provider.id,
                                onClick = {
                                    if (provider.id == ModelProviders.CUSTOM_ID) {
                                        providerHint = provider.note
                                    } else {
                                        // 预设只把地址和「建议的模型名」填好；模型名该选哪一个，
                                        // 仍然以「连接测试」列出来的清单为准（各家模型会改名字）
                                        baseUrl = provider.baseUrl
                                        modelField = provider.chatModel
                                        providerHint = "已填入「" + provider.name + "」的地址和模型名：" +
                                            "点「连接测试」验一下，测通再点「连接」。" + provider.note
                                    }
                                },
                                label = { Text(provider.name) },
                            )
                        }
                    }
                    providerHint?.let { hint ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // ① 接口地址 —— 没填或填错时这一格闪红
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            if (badUrl) badUrl = false
                        },
                        label = { Text("接口地址（Base URL）") },
                        singleLine = true,
                        isError = badUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(flashTint),
                        supportingText = {
                            Text(
                                if (badUrl) {
                                    "地址不对：域名写错了，或者少了 /v1"
                                } else {
                                    "填到 /v1 为止就行，末尾的 /chat/completions 会自动补上"
                                }
                            )
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    // ② API Key —— 同样会闪红
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            if (badKey) badKey = false
                        },
                        label = { Text("API Key") },
                        singleLine = true,
                        isError = badKey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(flashTint),
                        visualTransformation = if (showKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        supportingText = {
                            if (badKey) {
                                Text("Key 没填或不对：去服务商控制台复制一个，前后别带空格")
                            }
                        },
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "显示或隐藏",
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    // ③ 连接测试：只验「地址 + Key 能不能列出模型」，不动任何设置
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { runConnectTest() },
                            enabled = testState != ConnectState.TESTING,
                        ) {
                            if (testState == ConnectState.TESTING) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("连接测试")
                        }
                        Spacer(Modifier.width(12.dp))
                        // 状态灯（SillyTavern 那个绿点「有效的」的等价物）
                        val dotColor = when (testState) {
                            ConnectState.OK -> Color(0xFF2E7D32)
                            ConnectState.FAIL -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outline
                        }
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(dotColor, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (testState) {
                                ConnectState.OK -> "连上了"
                                ConnectState.FAIL -> "连不上"
                                ConnectState.TESTING -> "正在测…"
                                ConnectState.IDLE -> "还没测"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    testError?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // ④ 模型名：一格管两用（文本和看图都用它）。测通之后右边那枚箭头才点得动。
                    Box {
                        OutlinedTextField(
                            value = modelField,
                            onValueChange = { modelField = it },
                            label = { Text("模型名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { if (testState == ConnectState.OK) modelMenu = true },
                                    enabled = testState == ConnectState.OK,
                                ) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "查看可选模型")
                                }
                            },
                            supportingText = {
                                Text(
                                    when {
                                        testState == ConnectState.OK && models.isEmpty() ->
                                            "这个接口没返回模型列表，模型名手填也能用"
                                        testState == ConnectState.OK ->
                                            "已读到 " + models.size + " 个模型，点右边箭头挑一个"
                                        else ->
                                            "先点「连接测试」，测通之后才能从下拉里选模型"
                                    }
                                )
                            },
                        )
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            models.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (model.supportsImage) model.id + "（支持图像）" else model.id)
                                    },
                                    onClick = {
                                        modelField = model.id
                                        modelMenu = false
                                    },
                                )
                            }
                            if (models.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("没有可选模型") },
                                    onClick = { modelMenu = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // ⑤ 连接 = 存盘。没测通、或没选模型时点不动。
                        Button(
                            onClick = {
                                val picked = modelField.trim()
                                viewModel.updateSettings {
                                    it.copy(
                                        apiKey = apiKey.trim(),
                                        baseUrl = baseUrl.trim(),
                                        chatModel = picked,
                                        // 一格管两用：发图时也用这个模型。
                                        // 只吃文字的服务商请把「拍题识别 → 识别路线」设成纯文字。
                                        visionModel = picked,
                                    )
                                }
                                providerHint = "已连接：" + picked + "，拍题和问 AI 都会走它。"
                            },
                            enabled = testState == ConnectState.OK && modelField.isNotBlank(),
                        ) { Text("连接") }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (settings.configured && settings.chatModel.isNotBlank()) {
                                "正在用：" + settings.chatModel
                            } else {
                                "还没配好"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "接只吃文字的服务商（多数免费档、本地小模型）时，把下面「拍题识别」里的识别路线设成「纯文字」——" +
                            "这条设置决定这一问要不要把截图一起发过去，硬塞图片给它会直接报错。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard("免费通道（可选）") {
                    Text(
                        "再配一条 OpenAI 兼容的接口，结果面板上就会多一个「免费解析」按钮。\n" +
                            "简单题、只想要个解释的题走它，不花 DeepSeek 的钱。不填 Key 这个按钮就不出现。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = freeApiKey,
                        onValueChange = { freeApiKey = it },
                        label = { Text("API Key（免费通道）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showFreeKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showFreeKey = !showFreeKey }) {
                                Icon(
                                    if (showFreeKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "显示或隐藏",
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = freeBaseUrl,
                        onValueChange = { freeBaseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("默认 " + AppSettings.DEFAULT_FREE_BASE_URL) },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = freeModel,
                        onValueChange = { freeModel = it },
                        label = { Text("模型名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("默认 " + AppSettings.DEFAULT_FREE_MODEL) },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "默认填的是智谱 GLM 的免费档。它在视觉上不保证能用，所以免费通道一律只发文字、" +
                            "不发图片；换成别家的免费模型改上面两项即可。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(
                                    freeBaseUrl = freeBaseUrl.trim()
                                        .ifEmpty { AppSettings.DEFAULT_FREE_BASE_URL },
                                    freeApiKey = freeApiKey.trim(),
                                    freeModel = freeModel.trim()
                                        .ifEmpty { AppSettings.DEFAULT_FREE_MODEL },
                                )
                            }
                        },
                    ) { Text("保存免费通道") }
                }
            }

            item {
                SectionCard("拍题识别") {
                    Text("识别路线", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = settings.recognizer == Recognizer.AUTO,
                            onClick = { viewModel.updateSettings { it.copy(recognizer = Recognizer.AUTO) } },
                            label = { Text("自动") },
                        )
                        FilterChip(
                            selected = settings.recognizer == Recognizer.VISION,
                            onClick = { viewModel.updateSettings { it.copy(recognizer = Recognizer.VISION) } },
                            label = { Text("始终带图") },
                        )
                        FilterChip(
                            selected = settings.recognizer == Recognizer.OCR,
                            onClick = { viewModel.updateSettings { it.copy(recognizer = Recognizer.OCR) } },
                            label = { Text("只用本地 OCR") },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "自动：本地 OCR 够清楚就只发文字（省图片 token），识别质量差才把图片交给视觉模型。\n" +
                            "始终带图：每次都发图，公式图形最准，也最费 token。\n" +
                            "只用本地 OCR：完全离线，不消耗任何 token。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("图片精度", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("low" to "省流量", "high" to "清晰", "original" to "原图").forEach { (value, label) ->
                            FilterChip(
                                selected = settings.imageDetail == value,
                                onClick = { viewModel.updateSettings { it.copy(imageDetail = value) } },
                                label = { Text(label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "low 会把图缩到 512×512，更快更省；服务端本来就会把图缩到约 1300×1300，单图最多算 1024 token。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard("生成参数") {
                    Text(
                        "温度 " + String.format("%.2f", settings.temperature),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = settings.temperature.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateSettings { it.copy(temperature = value.toDouble()) }
                        },
                        valueRange = 0f..1.5f,
                    )
                    Text(
                        "解理科题建议 0.0 ~ 0.3，越低越稳定。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("最大输出 " + settings.maxTokens + " tokens", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = settings.maxTokens.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateSettings { it.copy(maxTokens = value.toInt()) }
                        },
                        valueRange = 512f..8192f,
                        steps = 14,
                    )
                }
            }

            item {
                SectionCard("提示词") {
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text("系统提示词") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        supportingText = {
                            TextButton(onClick = { systemPrompt = Prompts.DEFAULT_SYSTEM }) { Text("恢复默认") }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = answerPrompt,
                        onValueChange = { answerPrompt = it },
                        label = { Text("提问模板") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        supportingText = {
                            TextButton(onClick = { answerPrompt = Prompts.DEFAULT_ANSWER }) { Text("恢复默认") }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(
                                    apiKey = apiKey.trim(),
                                    baseUrl = baseUrl.trim(),
                                    chatModel = modelField.trim().ifEmpty { it.chatModel },
                                    visionModel = modelField.trim().ifEmpty { it.chatModel },
                                    systemPrompt = systemPrompt,
                                    answerPrompt = answerPrompt,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存提示词") }
                }
            }

            item {
                SectionCard("悬浮球与按钮条") {
                    Text("悬浮球大小 " + settings.bubbleSizeDp + " dp", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = settings.bubbleSizeDp.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateSettings { it.copy(bubbleSizeDp = value.toInt()) }
                        },
                        valueRange = 40f..72f,
                        steps = 31,
                    )
                    Text("悬浮球不透明度 " + settings.bubbleAlphaPercent + "%", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = settings.bubbleAlphaPercent.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateSettings { it.copy(bubbleAlphaPercent = value.toInt()) }
                        },
                        valueRange = 20f..100f,
                        steps = 15,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "按钮条不透明度 " + settings.actionsAlphaPercent + "%",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = settings.actionsAlphaPercent.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateSettings { it.copy(actionsAlphaPercent = value.toInt()) }
                        },
                        valueRange = 20f..100f,
                        steps = 15,
                    )
                    Text(
                        "按钮条就是点悬浮球弹出来的那一条（继续收录 / 搜题 / 拍题 / 拍照 / 关闭）。\n" +
                            "它横着占得宽，压在题目或解析上的时候挡得比悬浮球厉害 —— 嫌挡就拖淡一点，" +
                            "想要和以前一样就拖回 100%。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "结果卡片 / 搜题条不透明度 " + settings.panelAlphaPercent + "%",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = settings.panelAlphaPercent.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateSettings { it.copy(panelAlphaPercent = value.toInt()) }
                        },
                        valueRange = 20f..100f,
                        steps = 15,
                    )
                    Text(
                        "结果卡片就是「题库里没有这道题」那张（有答案时是同一张卡片）。\n" +
                            "搜题条是它的一部分：搜题时卡片顶上多出来的那条输入框、以及收起后只剩一条框的样子，" +
                            "和卡片是同一个窗口，所以一起淡。\n" +
                            "这张卡片面积最大、压在题目上最挡字，但答案也要看得清 —— 拖到多少合适由你定，" +
                            "拖回 100% 就是原样。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard("题库维护") {
                    Text(
                        "把已经收进题库的题重新过一遍文字清洗：" +
                            "繁体写法（納泵 → 纳泵）、选项前被认成字母的圆圈（DD. → D.）、" +
                            "粘在折行上的字母（E行 + 行 → 行）。\n" +
                            "清洗规则是在收录那一刻生效的，所以以前存下来的题不会自己变好；" +
                            "规则以后改进了，再点一次就行。攒到上千道也不用一条条手改。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (tidying) return@Button
                            tidying = true
                            tidyResult = null
                            tidyScope.launch {
                                val report = withContext(Dispatchers.IO) {
                                    runCatching { app.repository.tidyStoredText() }.getOrNull()
                                }
                                tidying = false
                                tidyResult = if (report == null) {
                                    "整理失败，看看日志"
                                } else {
                                    "扫描 " + report.scanned + " 道，修正 " + report.fixed + " 道" +
                                        if (report.samples.isEmpty()) {
                                            ""
                                        } else {
                                            "\n" + report.samples.joinToString("\n")
                                        }
                                }
                            }
                        },
                        enabled = !tidying,
                    ) {
                        if (tidying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("整理题库文字")
                        }
                    }
                    tidyResult?.let { report ->
                        Spacer(Modifier.height(8.dp))
                        Text(report, style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "找出相似题：把题库里像到该留一条的题成组列出来，逐组挑「留哪条」还是「都留」。\n" +
                            "相似是程序按字符串相似度算的（一模一样的那种一定能认出来），" +
                            "标着「疑似」的可能只是同一道题的两个版本 —— 删不删由你点。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            if (findingSimilar) return@OutlinedButton
                            findingSimilar = true
                            similarMessage = null
                            tidyScope.launch {
                                val report = withContext(Dispatchers.IO) {
                                    runCatching { app.repository.findSimilarQuestions() }.getOrNull()
                                }
                                findingSimilar = false
                                similarMessage = when {
                                    report == null -> "找相似题失败了，看看日志"
                                    report.groups.isEmpty() ->
                                        // 光说「没有相似题」用户没法判断 —— 把「最像的一对」也摆出来：
                                        // 差一点没到线，和压根不像，是两件事（2026-10-13 用户就卡在这儿）
                                        "扫描 " + report.scanned + " 道，没有发现相似题。" +
                                            "最像的一对：相似度 " + (report.bestMissScore * 100).toInt() +
                                            "%，一条有 " + (report.bestMissCoverage * 100).toInt() +
                                            "% 的内容能在另一条里找到（这两条要到 90% 才算「没收完的半截」）。"

                                    else -> "扫描 " + report.scanned + " 道，发现 " +
                                        report.groups.size + " 组相似题" +
                                        (if (report.partialCount > 0) {
                                            "（其中 " + report.partialCount + " 组是「没收完的半截」）"
                                        } else {
                                            ""
                                        }) + "，最多能少 " + report.removable + " 道。"
                                }
                                // 一组都没有就不弹空对话框了，把那句话留在按钮下面
                                if (report != null && report.groups.isNotEmpty()) {
                                    similarReport = report
                                }
                            }
                        },
                        enabled = !findingSimilar,
                    ) {
                        if (findingSimilar) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("找出相似题")
                        }
                    }
                    similarMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("关于费用", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "只在拍题和问答时才会调用 DeepSeek，题库导入、检索、匹配全部在手机本地完成。\n" +
                                "图片按尺寸折算 token，单张最多 1024；「自动」模式在文字够清楚时完全不发图。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // 相似题对话框。它是个全屏 Dialog，摆在 Scaffold 外面才不会跟着设置页一起滚。
    similarReport?.let { report ->
        SimilarQuestionsDialog(
            report = report,
            busy = similarBusy,
            onDismiss = { similarReport = null },
            onConfirm = { deleteIds ->
                similarBusy = true
                tidyScope.launch {
                    val removed = withContext(Dispatchers.IO) {
                        runCatching { app.repository.deleteQuestionsByIds(deleteIds) }.getOrNull() ?: 0
                    }
                    similarBusy = false
                    similarReport = null
                    similarMessage = if (removed == 0) {
                        "没有删掉任何题"
                    } else {
                        "清理完成，删掉 " + removed + " 道。再点一次「找出相似题」可以继续清。"
                    }
                }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * 「连接测试」的四种状态。
 *
 * 之所以要一个显式状态机而不是两个 Boolean：界面上三处（状态灯、模型框能不能点、连接按钮能不能点）
 * 都读它，布尔一多就会漏掉组合（比如「测试中」时连接按钮还能点）。
 */
private enum class ConnectState { IDLE, TESTING, OK, FAIL }