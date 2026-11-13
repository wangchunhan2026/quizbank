package com.daily.quizbank.service

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.daily.quizbank.data.db.QuestionMatch
import com.daily.quizbank.util.AnswerText
import com.daily.quizbank.util.MatchHighlight
import com.daily.quizbank.util.Similarity
import kotlin.math.roundToInt

/**
 * 悬浮的结果卡片。
 *
 * 高度固定而不是 wrap_content：答案边流边加，卡片如果自适应高度就会一直跳，
 * 用户的手指刚好停在按钮上时会被甩走。固定高度 + 内部滚动是更稳的交互。
 */
class ResultPanel(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    var onRetry: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    /** 点「收录」：跳到独立的收录界面去编辑并入库。 */
    var onCollect: (() -> Unit)? = null

    /**
     * 用户在候选卡片里点了某一条。
     *
     * 同一道题干在题库里可能同时存在单选题和判断题（题干一模一样、答案不同），
     * 这时面板会列出好几条候选。不选就猜一条去问 AI，AI 解释的可能是另一条，
     * 用户还以为是自己的答案被解释了 —— 所以必须先让他指定。
     */
    var onMatchSelected: ((Int) -> Unit)? = null

    /** 走免费通道问 AI。 */
    var onAiFree: (() -> Unit)? = null

    /** 走 DeepSeek（付费）通道问 AI。 */
    var onAiPaid: (() -> Unit)? = null

    /** 搜题模式下点「搜索」，或者按输入法上的搜索键。 */
    var onSearch: ((String) -> Unit)? = null

    /** 搜题模式下按「收起」：折回只有一条输入框的样子，而不是把整块关掉。 */
    var onCollapse: (() -> Unit)? = null

    /**
     * 搜题模式。
     *
     * 和拍题模式有三处不同，全都是「要在卡片里打字」逼出来的：
     *
     *  1. 卡片顶部多一条输入框（拍题时顶部是标题栏 + 拖动把手）；
     *  2. 窗口**不能**带 FLAG_NOT_FOCUSABLE —— 拍题模式特意带着它，为的是不抢宿主应用的
     *     输入焦点，但输入框拿不到焦点就弹不出键盘；
     *  3. 铺满屏幕宽度并咬住下沿，输入框越宽越好用。
     */
    var searchMode = false

    /**
     * 搜题模式下「还没搜」的样子：整张卡片只剩那一条输入框。
     *
     * 「先只有一个搜索框，搜完再在下面长出答案」就是靠这个状态切换做的。
     * 窗口高度是加进 WindowManager 时定死的、之后改不可靠，所以切换走拆掉重建，
     * 见 [rebuild]。
     */
    var collapsed = false

    /**
     * 输入框里的原文。
     *
     * 卡片每次切换状态都是整个拆了重建，视图里的内容留不住；
     * 搜索完展开面板时不能把用户刚打的字弄丢，所以原文单独存在这儿。
     */
    var query = ""

    private var searchField: EditText? = null
    private var actionsRow: View? = null
    private var resizeHandle: View? = null

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var questionView: TextView? = null
    private var questionLabel: TextView? = null
    private var matchContainer: LinearLayout? = null
    private var matchLabel: TextView? = null

    /** 当前列出来的候选，以及用户选了第几条。选中项决定「解析」解释的是哪个答案。 */
    private var shownMatches: List<QuestionMatch> = emptyList()
    private var selectedMatchIndex = 0
    private var thinkingView: TextView? = null
    private var thinkingToggle: TextView? = null

    /** 思维链原文。折叠只是不显示，内容一直留着，用户点标题随时能展开看。 */
    private val thinkingBuffer = StringBuilder()
    private var answerView: TextView? = null
    private var answerLabel: TextView? = null
    private var scroll: ScrollView? = null
    /** 最后一次 setupAiButtons 的入参，恢复快照时照着重放一遍。 */
    private var lastAiSetup: Triple<Boolean, Boolean, Boolean>? = null

    private var aiRow: View? = null
    private var aiFreeButton: TextView? = null
    private var aiPaidButton: TextView? = null
    private var retryButton: TextView? = null
    private var collectButton: TextView? = null

    /** 结论区那段文字是不是题库给的，恢复快照时要用。 */
    private var answerFromBank = true

    /**
     * 「重拍」按钮的文案。
     *
     * 截屏来的题在屏幕上，拍照来的题在纸或另一台设备上，两者「再来一次」的动作
     * 根本不是同一件事 —— 按钮得说实话，否则用户按下去会一脸问号。
     */
    fun setRetryLabel(text: String) {
        retryButton?.text = text
    }

    /**
     * 配好这张结果上的两个 AI 按钮。
     *
     * [explaining] 决定叫「解析」还是「解答」—— 对用户来说是两件事：
     * 一个是「告诉我为什么是这个答案」，一个是「这题到底怎么做」。
     * 命中题库能给出确定答案时是前者，只有候选或干脆没命中时是后者。
     *
     * [freeConfigured] / [paidConfigured] 决定哪几个露面：没配通道的按钮不显示，
     * 免得点下去只换来一句「去设置里填 Key」。
     */
    fun setupAiButtons(explaining: Boolean, freeConfigured: Boolean, paidConfigured: Boolean) {
        lastAiSetup = Triple(explaining, freeConfigured, paidConfigured)
        val suffix = if (explaining) "解析" else "解答"
        aiFreeButton?.apply {
            text = "免费" + suffix
            visibility = if (freeConfigured) View.VISIBLE else View.GONE
        }
        aiPaidButton?.apply {
            text = "深度" + suffix
            visibility = if (paidConfigured) View.VISIBLE else View.GONE
        }
        // 两个都不露面时整行让位，别在动作区上面留一条空带
        aiRow?.visibility = if (freeConfigured || paidConfigured) View.VISIBLE else View.GONE
        requestFit()
    }

    /** 两个 AI 按钮全收起来（开始作答之后就没得再点了）。 */
    fun hideAiButtons() = setupAiButtons(explaining = false, freeConfigured = false, paidConfigured = false)

    /**
     * 明确标出这段答案的来源。
     * 用颜色区分不只是好看 —— 用户需要一眼看出「这是题库里的标准答案」
     * 还是「这是 AI 生成的」，两者的可信度完全不同。
     */
    fun setAnswerLabel(text: String, fromBank: Boolean) {
        answerFromBank = fromBank
        val label = answerLabel ?: return
        label.text = text
        label.setTextColor(if (fromBank) 0xFF4ADE80.toInt() else 0xFF7DD3FC.toInt())
    }

    private var autoScroll = true

    /**
     * 上一次填进答案区的那段原文。用来分辨两种情形：
     *
     *  - **一段段写出来的 AI 答案**（每次的文本都是上一次的延长）→ 跟着尾巴往下滚，不然看不到字在长；
     *  - **题库一次给完的结论**（文本是整段换掉）→ **不滚**，停在原处。
     */
    private var lastAnswerText = ""

    /** 用户自己拖过高度把手。拖过之后就别再按内容自动改高度了，那是跟他抢。 */
    private var userResized = false

    /**
     * 高度已经按「有答案」定过了。
     *
     * 答案是一段段流进来的，每来一段就按内容重算一次高度的话，卡片会一直抽动 ——
     * 用户的手指刚好停在按钮上时会被甩走（理由见类注释）。所以答案一出现就把高度定死。
     */
    private var heightPinned = false

    val attached: Boolean get() = root != null

    /**
     * 整张卡片的不透明度（含搜题模式下顶上那条输入框）。
     *
     * 卡片压在题目或解析上时会挡字，所以和悬浮球、按钮条一样要能调（用户 2026-10-13 追加要的）。
     * 值存在这里、而不是让外面直接改 root.alpha：卡片是**拆了重建**的（见 [rebuild]），
     * 重建出来的是新视图，不重新套一遍这个值的话，切一次状态透明度就丢了。
     */
    var alpha = 1f
        set(value) {
            field = value
            root?.alpha = value
        }

    fun show() {
        if (root == null) build()
        val view = root ?: return
        if (view.parent == null) {
            runCatching { windowManager.addView(view, params) }
        }
        view.visibility = View.VISIBLE

        if (searchMode && collapsed) {
            // 打开就把光标放进输入框、键盘弹起来，抬手就能打字。
            // 推到下一帧是因为 addView 刚返回时窗口还没拿到焦点，
            // 这时候 requestFocus / showSoftInput 会被系统直接忽略。
            view.post { focusSearchField() }
        }
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        params = null
        titleView = null
        subtitleView = null
        questionView = null
        questionLabel = null
        matchContainer = null
        matchLabel = null
        shownMatches = emptyList()
        selectedMatchIndex = 0
        thinkingView = null
        thinkingToggle = null
        thinkingBuffer.setLength(0)
        lastAnswerText = ""
        answerView = null
        answerLabel = null
        retryButton = null
        collectButton = null
        searchField = null
        actionsRow = null
        resizeHandle = null
        aiRow = null
        aiFreeButton = null
        aiPaidButton = null
        scroll = null
    }

    /**
     * 拆掉重建。
     *
     * 高度是 [build] 里一次算好、随窗口一起交给 WindowManager 的，加进去之后改不可靠；
     * 而搜题要在「一条输入框」和「输入框 + 结果」两种高度之间来回切，只能重建。
     * 重建会把输入框内容一起丢掉，所以调用方要先把它存进 [query]。
     */
    private fun rebuild() {
        hide()
        show()
    }

    /** 搜题：从「只有一条输入框」展开成带结果面板的卡片。 */
    fun expandSearch(text: String) {
        query = text
        collapsed = false
        rebuild()
    }

    /** 搜题：折回只有一条输入框的样子，输入框里保留原来的题干，方便改两个字再搜。 */
    fun collapseSearch() {
        collapsed = true
        rebuild()
    }

    /**
     * 卡片当前的内容。
     *
     * 存在的理由：收录界面是个独立 Activity，而这张卡片是 overlay 窗口 ——
     * overlay 永远浮在 Activity 之上，不收掉的话收录界面会被它整个盖住。
     * 收掉意味着视图全部销毁，取消回来就没东西可显示了；
     * 所以先把内容抄一份出来，回来时重建再填回去。
     *
     * 抄的是**用户看得见的东西**，不是内部状态：结论、状态行、候选、思维链。
     * 问 AI 那两个按钮的可见性也一并带上，否则取消回来按钮就没了。
     */
    data class Snapshot(
        val question: String,
        val answer: String,
        val answerLabel: String,
        val answerFromBank: Boolean,
        val statusTitle: String,
        val statusSubtitle: String,
        val thinking: String,
        val matches: List<QuestionMatch>,
        val selectedIndex: Int,
        val aiSetup: Triple<Boolean, Boolean, Boolean>?,
    )

    fun snapshot(): Snapshot = Snapshot(
        question = questionView?.text?.toString().orEmpty(),
        answer = answerView?.text?.toString().orEmpty(),
        answerLabel = answerLabel?.text?.toString().orEmpty(),
        answerFromBank = answerFromBank,
        statusTitle = titleView?.text?.toString().orEmpty(),
        statusSubtitle = subtitleView?.text?.toString().orEmpty(),
        thinking = thinkingBuffer.toString(),
        matches = shownMatches,
        selectedIndex = selectedMatchIndex,
        aiSetup = lastAiSetup,
    )

    /** 把 [snapshot] 抄下来的内容填回一张刚重建好的卡片。必须在 show() 之后调用。 */
    fun restore(s: Snapshot) {
        setQuestion(s.question)
        setMatches(s.matches, s.selectedIndex)
        setAnswer(s.answer)
        if (s.answerLabel.isNotBlank()) setAnswerLabel(s.answerLabel, s.answerFromBank)
        setStatus(s.statusTitle, s.statusSubtitle)
        if (s.thinking.isNotEmpty()) appendThinking(s.thinking)
        s.aiSetup?.let { (explaining, free, paid) -> setupAiButtons(explaining, free, paid) }
    }

    /** 把焦点放进输入框、键盘弹起来。搜题刚打开时要抬手就能打字。 */
    fun focusSearchField() {
        val field = searchField ?: return
        field.requestFocus()
        field.setSelection(field.text?.length ?: 0)
        runCatching {
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun setStatus(title: String, subtitle: String) {
        titleView?.text = title
        subtitleView?.text = subtitle
        subtitleView?.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        requestFit()
    }

    fun setQuestion(text: String) {
        val label = questionLabel ?: return
        val body = questionView ?: return
        if (text.isBlank()) {
            label.visibility = View.GONE
            body.visibility = View.GONE
        } else {
            label.visibility = View.VISIBLE
            body.visibility = View.VISIBLE
            body.text = text
        }
        requestFit()
    }

    /**
     * 这次拍/搜的原文。题干里与它对得上的片段会标红，见 [MatchHighlight]。
     * 拍题/拍照传 OCR 原文，搜题传用户打进去的那段字。
     */
    var highlightSource: String = ""

    fun setMatches(matches: List<QuestionMatch>, selectedIndex: Int = 0) {
        shownMatches = matches
        selectedMatchIndex = selectedIndex
        renderMatches()
    }

    /** 用户换了选中项时重画候选区。 */
    fun setSelectedMatch(index: Int) {
        if (index == selectedMatchIndex) return
        selectedMatchIndex = index
        renderMatches()
    }

    private fun renderMatches() {
        val container = matchContainer ?: return
        val label = matchLabel ?: return
        container.removeAllViews()
        if (shownMatches.isEmpty()) {
            label.visibility = View.GONE
            return
        }
        label.visibility = View.VISIBLE
        val multiple = shownMatches.size > 1
        label.text = "本地题库匹配（" + shownMatches.size + "）" +
            if (multiple) " · 点一下选定哪条" else ""
        shownMatches.forEachIndexed { index, match ->
            container.addView(buildMatchCard(match, index, multiple))
        }
        requestFit()
    }

    private fun buildMatchCard(match: QuestionMatch, index: Int, selectable: Boolean): View {
        val selected = selectable && index == selectedMatchIndex
        val card = OverlayUi.vertical(context).apply {
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            // 选中那条给个描边 + 底色，因为「解析」解释的就是它 ——
            // 用户得一眼看出自己选中的是哪一条，否则会以为 AI 在解释另一条。
            setBackground(
                OverlayUi.rounded(
                    if (selected) 0xFF2B3B54.toInt() else 0xFF232A36.toInt(),
                    dp(10f).toFloat(),
                    if (selected) 0xFF4ADE80.toInt() else android.graphics.Color.TRANSPARENT,
                    if (selected) dp(1f) else 0,
                ),
            )
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(8f)
            layoutParams = lp
            if (selectable) {
                isClickable = true
                setOnClickListener { onMatchSelected?.invoke(index) }
            }
        }

        val percent = (match.score * 100).roundToInt()
        val header = OverlayUi.horizontal(context)
        header.addView(
            OverlayUi.text(context, "相似度 " + percent + "%", 11f, 0xFF7DD3FC.toInt(), bold = true),
        )
        // 相似的题经常一次给好几条，用户要照着这些候选自己判断。
        // 低分的那些必须标出来 —— 一条 55% 的候选看起来和 95% 的一样「像个答案」，
        // 但它很可能只是题干里恰好有几个词一样。
        if (match.score < LOW_CONFIDENCE) {
            header.addView(
                OverlayUi.text(context, "仅参考", 11f, 0xFFF59E0B.toInt()).apply {
                    setPadding(dp(6f), 0, 0, 0)
                },
            )
        }
        // 搜题时这条的**解析**也对上了这次粘进来的字。用户 2026-10-12 要的排序就是照它排的，
        // 不说一句他看不出为什么这条排到了前面（拍题不开这个，所以拍题时这里永远不出现）。
        if (match.analysisScore >= Similarity.MATCH_THRESHOLD) {
            header.addView(
                OverlayUi.text(context, "解析也对上", 11f, 0xFF7DD3FC.toInt()).apply {
                    setPadding(dp(6f), 0, 0, 0)
                },
            )
        }
        if (selectable) {
            header.addView(
                OverlayUi.text(
                    context,
                    if (selected) "● 解析这条" else "点一下选它",
                    11f,
                    if (selected) 0xFF4ADE80.toInt() else 0xFF64748B.toInt(),
                    bold = selected,
                ).apply { setPadding(dp(6f), 0, 0, 0) },
            )
        }
        header.addView(OverlayUi.spacer(context))
        if (match.bankName.isNotBlank()) {
            header.addView(OverlayUi.text(context, match.bankName, 11f, 0xFF94A3B8.toInt()))
        }
        card.addView(header)

        val stemView = OverlayUi.text(context, match.question.stem, 13f, 0xFFE5E7EB.toInt()).apply {
            setPadding(0, dp(6f), 0, 0)
        }
        // 题干里与这次拍/搜的原文**对得上**的片段标红，其余照旧白色 ——
        // 题库那条和屏幕上这条差一两个字时，用户一眼就能看出差在哪儿（2026-10-09 用户要的）。
        // 只有题干这么干：选项和解析本来就不该参与「像不像」的判断。
        val marks = MatchHighlight.ranges(highlightSource, match.question.stem)
        if (marks.isNotEmpty()) {
            stemView.text = SpannableString(match.question.stem).apply {
                marks.forEach { range ->
                    val end = (range.last + 1).coerceAtMost(match.question.stem.length)
                    setSpan(
                        ForegroundColorSpan(android.graphics.Color.rgb(255, 92, 92)),
                        range.first,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        card.addView(stemView)

        // 选项必须原样列出来。面板经常一次给出好几条候选（相似度 100% / 53% …）
        // 让用户自己挑，而只写一句「答案：C」的话，他既不知道 C 是什么，
        // 也没法判断这条到底对不对 —— 拍题时题目早就滚出屏幕了，回头对不上。
        val options = match.question.options
        val answerLetters = match.question.answer.trim().uppercase().filter { it in 'A'..'H' }
        options.forEachIndexed { index, option ->
            val isAnswer = AnswerText.letterOf(index) in answerLetters
            card.addView(
                OverlayUi.text(
                    context,
                    AnswerText.optionLine(index, option),
                    12f,
                    if (isAnswer) 0xFF4ADE80.toInt() else 0xFF9CA3AF.toInt(),
                    bold = isAnswer,
                ).apply { setPadding(0, dp(4f), 0, 0) },
            )
        }

        val answer = match.question.answer.trim()
        if (answer.isNotEmpty()) {
            card.addView(
                OverlayUi.text(
                    context,
                    "答案：" + AnswerText.resolve(answer, options),
                    13f,
                    0xFF4ADE80.toInt(),
                    bold = true,
                ).apply { setPadding(0, dp(6f), 0, 0) },
            )
        }

        val analysis = match.question.analysis.trim()
        if (analysis.isNotEmpty()) {
            card.addView(
                OverlayUi.text(context, "解析：" + analysis, 12f, 0xFF9CA3AF.toInt()).apply {
                    setPadding(0, dp(6f), 0, 0)
                },
            )
        }
        return card
    }

    /**
     * 追加思维链。
     *
     * 默认**折叠**：推理过程常常几千字，全铺开来会把结论挤到屏幕外面去，
     * 而用户要的是答案。想看过程就点一下那行标题展开，内容一直留在 buffer 里。
     */
    fun appendThinking(text: String) {
        if (text.isEmpty()) return
        thinkingBuffer.append(text)
        val toggle = thinkingToggle ?: return
        toggle.visibility = View.VISIBLE
        if (thinkingView?.visibility == View.VISIBLE) {
            thinkingView?.append(text)
            maybeScroll()
        }
        refreshThinkingToggle()
    }

    private fun refreshThinkingToggle() {
        val toggle = thinkingToggle ?: return
        val expanded = thinkingView?.visibility == View.VISIBLE
        toggle.text = "思考过程 " + thinkingBuffer.length + " 字 · " +
            if (expanded) "点击收起 ▾" else "点击展开 ▸"
    }

    private fun toggleThinking() {
        val body = thinkingView ?: return
        if (body.visibility == View.VISIBLE) {
            body.visibility = View.GONE
        } else {
            body.text = thinkingBuffer.toString()
            body.visibility = View.VISIBLE
        }
        refreshThinkingToggle()
        maybeScroll()
    }

    private fun resetThinking() {
        thinkingBuffer.setLength(0)
        thinkingView?.text = ""
        thinkingView?.visibility = View.GONE
        thinkingToggle?.visibility = View.GONE
    }

    fun setAnswer(text: String) {
        val label = answerLabel ?: return
        val body = answerView ?: return
        if (text.isEmpty()) {
            // 每次拍题开头都会用空串重置一次，顺手把上一轮的思维链清掉
            resetThinking()
            label.visibility = View.GONE
            body.visibility = View.GONE
            // 上一道题的答案清掉了，高度重新按内容走（这一道可能压根没有答案）
            heightPinned = false
            lastAnswerText = ""
            requestFit()
            return
        }
        label.visibility = View.VISIBLE
        body.visibility = View.VISIBLE
        // 「这次是接着上次往下写」= 上一次那段是这次这段的前缀。
        // 题库给的结论是整段换掉的，一次都不算接续。
        val streaming = lastAnswerText.isNotEmpty() && text.startsWith(lastAnswerText)
        lastAnswerText = text
        body.text = text
        // 答案一出现就把思考过程收起来（不是丢掉，点那行标题还能展开）
        thinkingView?.visibility = View.GONE
        refreshThinkingToggle()
        // 答案要给足地方看，以后高度不再跟着内容变
        pinFullHeight()
        // **只有正在一段段写的答案才往下跟。**
        //
        // 2026-10-12 用户报的：「拍题时有时默认跳到最底部，而不是顶部」——
        // 一命中题库，这里就 fullScroll 到最底，把上面的题干和候选里**标红的字**全顶出屏幕，
        // 而那正是他这次想看的（标红是同一轮刚加的功能）。
        // 题库的结论是一次给完的，停在原处就好，他自己要往下看会滑。
        if (streaming) maybeScroll()
    }

    fun currentAnswer(): String = answerView?.text?.toString().orEmpty()

    private fun maybeScroll() {
        if (!autoScroll) return
        val view = scroll ?: return
        view.post { runCatching { view.fullScroll(View.FOCUS_DOWN) } }
    }

    // ------------------------------------------------------------ 高度按内容走

    /**
     * 高度跟着内容走，内容少了就把卡片收回去。
     *
     * 用户报的是「题库里没有这道题那张卡片太大」（2026-10-09）：那条状态下卡片里只有
     * 一行状态、一段题干和两个按钮，却仍然占着屏幕下面 45%，把题目和选项整个盖住 ——
     * 他正想对着题目自己算一遍，卡片先把地方占了。
     *
     * 只在**答案还没出现**的时候收；有答案了就该给足地方，见 [pinFullHeight]。
     */
    private fun requestFit() {
        val view = root ?: return
        // 内容刚塞进去、这一帧还没量过，量出来的是上一次的高度，所以推到下一帧
        view.post { fitHeightToContent() }
    }

    private fun fitHeightToContent() {
        if (searchMode || userResized || heightPinned) return
        applyHeight(measureNaturalHeight())
    }

    /** 答案来了：一次给足高度，之后不再自动改（理由见类注释）。 */
    private fun pinFullHeight() {
        if (heightPinned) return
        heightPinned = true
        applyHeight(maxPanelHeight())
    }

    /** 拍题模式下的默认高度，也是自动收缩的上限。 */
    private fun maxPanelHeight(): Int {
        val metrics = context.resources.displayMetrics
        return minOf((metrics.heightPixels * 0.45f).roundToInt(), dp(470f))
    }

    /**
     * 内容原样铺开会占多高。
     *
     * 量之前得把滚动区临时改成 WRAP_CONTENT：它平时是 weight = 1（把剩下的地方吃满），
     * 那样量出来永远是「别的部件 + 0」，问不出内容自己想要多高。
     * 量完必须 requestLayout 一次 —— 走完这一趟，各部件身上留的是「不限高」量出来的尺寸，
     * 不重排的话滚动区会按内容高度摊开，把下面那排按钮顶出卡片外面。
     */
    private fun measureNaturalHeight(): Int {
        val card = root ?: return 0
        val scroller = scroll ?: return 0
        val width = params?.width ?: 0
        if (width <= 0) return 0
        val saved = scroller.layoutParams
        scroller.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val natural = card.measuredHeight
        scroller.layoutParams = saved
        card.requestLayout()
        return natural
    }

    /**
     * 改窗口高度。
     *
     * 位置也是照着高度算出来的（贴着屏幕下沿放），所以高度一变位置要跟着挪，
     * 否则卡片会浮在半空。用户拖过高度之后这里就不再被调用，见 [userResized]。
     */
    private fun applyHeight(height: Int) {
        if (searchMode) return
        val card = root ?: return
        val lp = params ?: return
        val target = height.coerceIn(dp(MIN_PANEL_DP), maxPanelHeight())
        if (lp.height == target) return
        lp.height = target
        val metrics = context.resources.displayMetrics
        lp.y = (metrics.heightPixels - target - dp(10f)).coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(card, lp) }
    }

    // ------------------------------------------------------------------ 构建

    private fun build() {
        val metrics = context.resources.displayMetrics

        // 搜题模式铺满宽度并咬住屏幕下沿：输入框越宽越好用。
        val width = if (searchMode) {
            metrics.widthPixels
        } else {
            minOf((metrics.widthPixels * 0.93f).roundToInt(), dp(460f))
        }

        val height = when {
            // 搜题刚打开：整张卡片就只有那一条输入框
            searchMode && collapsed -> dp(SEARCH_BAR_DP)
            // 搜题展开后：输入框 + 结果，占屏幕下半
            searchMode -> minOf((metrics.heightPixels * 0.52f).roundToInt(), dp(560f))
            // 拍题：默认只占屏幕下面不到一半，而且贴着底边放。
            //
            // 以前默认 66% 高、从 12% 处往下铺，正好把题干和选项全盖住 —— 用户想对着题目
            // 核答案，每次都得先把它拖小。现在题面留在上面，面板占下面那块本来就没内容的区域。
            else -> minOf((metrics.heightPixels * 0.45f).roundToInt(), dp(470f))
        }

        val card = OverlayUi.vertical(context).apply {
            setBackground(OverlayUi.rounded(0xF210141B.toInt(), dp(16f).toFloat(), 0x33FFFFFF, dp(1f)))
            if (searchMode) {
                setPadding(dp(8f), dp(8f), dp(8f), dp(10f))
            } else {
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            }
        }

        // 搜题的顶部是输入框，拍题的顶部是标题栏（同时兼作拖动把手）
        card.addView(if (searchMode) buildSearchRow() else buildHeader())

        val status = if (searchMode) buildInlineStatus() else null
        status?.let { card.addView(it) }

        val scroller = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            setPadding(0, dp(6f), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        scroll = scroller

        val body = OverlayUi.vertical(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        questionLabel = sectionLabel(if (searchMode) "搜索的题干" else "识别到的题目")
        body.addView(questionLabel)
        questionView = bodyText().also { body.addView(it) }

        matchLabel = sectionLabel("本地题库匹配")
        body.addView(matchLabel)
        matchContainer = OverlayUi.vertical(context).also { body.addView(it) }

        answerLabel = sectionLabel("AI 解答")
        body.addView(answerLabel)

        thinkingToggle = OverlayUi.text(context, "", 12f, 0xFF7DD3FC.toInt(), bold = true).apply {
            visibility = View.GONE
            isClickable = true
            setPadding(0, dp(8f), 0, 0)
            setOnClickListener { toggleThinking() }
        }
        body.addView(thinkingToggle)

        thinkingView = bodyText(0xFF94A3B8.toInt(), italic = true).also {
            it.visibility = View.GONE
            body.addView(it)
        }
        answerView = bodyText().also { body.addView(it) }

        scroller.addView(body)
        card.addView(scroller)

        val actions = buildActions().also { actionsRow = it }
        card.addView(actions)

        val handle = buildResizeHandle().also { resizeHandle = it }
        card.addView(handle)

        // 搜题模式的高度由「收起 / 展开」这个状态决定，拖拽把手只会和它打架：
        // 拖完一搜，expandSearch() 重建时就复位了，白拖。
        if (searchMode) handle.visibility = View.GONE

        // 收起状态：整张卡片就一条输入框，别的一律不占地方。
        // 状态行也藏掉 —— 那一格高度只够放输入框，多一行就会被挤没。
        if (searchMode && collapsed) {
            status?.visibility = View.GONE
            scroller.visibility = View.GONE
            actions.visibility = View.GONE
        }

        setQuestion("")
        setMatches(emptyList())
        setStatus("", "")

        val layoutParams = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            windowFlags(),
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            if (searchMode) {
                gravity = Gravity.BOTTOM or Gravity.START
                @Suppress("DEPRECATION")
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    if (collapsed) {
                        // 刚打开，直接把键盘立起来，抬手就能打字
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    } else {
                        // 已经搜出结果了，键盘收掉，不然它会把答案那一半顶走
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    }
            } else {
                gravity = Gravity.TOP or Gravity.START
                x = ((metrics.widthPixels - width) / 2f).roundToInt().coerceAtLeast(0)
                y = (metrics.heightPixels - height - dp(10f)).coerceAtLeast(0)
            }
        }

        // 新视图要重新套一遍外面设过的透明度（见 alpha 的说明）
        card.alpha = alpha
        root = card
        params = layoutParams
    }

    /**
     * 两种模式的窗口标记完全相反，这是「能不能打字」的分水岭。
     *
     * 拍题：带 FLAG_NOT_FOCUSABLE。不带的话它会抢走宿主应用的输入焦点 —— 用户只是
     * 想看个答案，结果底下正在打字的应用被顶掉了。
     *
     * 搜题：**绝对不能**带 FLAG_NOT_FOCUSABLE，带了输入框就永远拿不到焦点、键盘弹不出来；
     * 同时必须带 FLAG_NOT_TOUCH_MODAL，否则这张铺满屏幕宽度的卡片会把整屏触摸全吃掉，
     * 用户连底下的应用都点不动。
     */
    private fun windowFlags(): Int = if (searchMode) {
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    } else {
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }

    // ------------------------------------------------------------------ 搜题输入

    /**
     * 搜题模式的顶部：输入框 + 粘贴 / 搜索 / ✕。
     *
     * 默认是「打字」—— 光标直接落在输入框里、键盘已经弹起来，抬手就能打；
     * 要粘整段题干才去点「粘贴」。
     *
     * 曾经写成「打开就自动把剪贴板内容填进来」，看着省一步，实际很碍事：
     * 剪贴板里多数时候是上一条无关内容（实测把小米笔记里的一段话填进来了），
     * 填进去之后用户想自己打字还得先全选删掉。
     */
    private fun buildSearchRow(): View {
        val row = OverlayUi.horizontal(context).apply {
            // 高度必须写死。行里的 EditText 和三个按钮都是 MATCH_PARENT，
            // 而卡片是竖向 LinearLayout、这一行的 layoutParams 如果是 WRAP_CONTENT，
            // MATCH_PARENT 就会解析成 0 —— 输入框会整条消失。
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(SEARCH_ROW_DP),
            )
        }

        val field = EditText(context).apply {
            hint = "输入题干，或点「粘贴」"
            setHintTextColor(0xFF64748B.toInt())
            setTextColor(0xFFE5E7EB.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            // 题干可以很长，允许多行，超出部分框内滚动（窗口高度是定死的，撑不开）
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = OverlayUi.rounded(0xFF232A36.toInt(), dp(10f).toFloat())
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(query)
            setSelection(query.length)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitSearch()
                    true
                } else {
                    false
                }
            }
            // 悬浮窗没有 Activity 兜返回键，不拦的话返回会被丢给底下的应用，
            // 表现成「按了返回，卡片还在，底下的应用却退了」。
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    hide()
                    onClose?.invoke()
                    true
                } else {
                    false
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        searchField = field

        row.addView(field)
        row.addView(barButton("搜索", 0xFF1F6FEB.toInt()) { submitSearch() })
        row.addView(barButton("✕", 0xFF2F3646.toInt()) { hide(); onClose?.invoke() })
        return row
    }

    /**
     * 搜题模式下的状态行（「正在匹配题库」「已命中你的题库 · 相似度 86%」这些）。
     *
     * 拍题模式的状态挂在标题栏里，搜题模式顶部让给输入框了，状态就单独站一行。
     * 仍然写进同一个 titleView / subtitleView，[setStatus] 不用为两种模式分叉。
     */
    private fun buildInlineStatus(): View {
        val box = OverlayUi.vertical(context).apply {
            setPadding(dp(4f), dp(8f), dp(4f), 0)
        }
        titleView = OverlayUi.text(context, "", 13f, Color.WHITE, bold = true)
        subtitleView = OverlayUi.text(context, "", 11f, 0xFF94A3B8.toInt()).apply {
            setPadding(0, dp(3f), 0, 0)
        }
        box.addView(titleView)
        box.addView(subtitleView)
        return box
    }

    /** 输入框右侧的小按钮。没复用 [OverlayUi.button]：那个左右各留 14dp，三个挤下来输入框就没地方了。 */
    private fun barButton(label: String, background: Int, onClick: () -> Unit): TextView =
        OverlayUi.text(context, label, 13f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            val padH = dp(11f)
            setPadding(padH, 0, padH, 0)
            setBackground(OverlayUi.rounded(background, dp(10f).toFloat()))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { marginStart = dp(7f) }
        }

    private fun submitSearch() {
        val text = searchField?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            // 空着点搜索是最常见的误触，别弹 toast，把光标还回去就行
            focusSearchField()
            return
        }
        // 搜索期间键盘必须收起来：答案就在这张卡片的下半部分，键盘一立起来就把它压没了
        hideKeyboard()
        onSearch?.invoke(text)
    }

    private fun hideKeyboard() {
        val token = root?.windowToken ?: return
        runCatching {
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun buildHeader(): View {
        val row = OverlayUi.horizontal(context)
        val titles = OverlayUi.vertical(context)
        titleView = OverlayUi.text(context, "拍题结果", 16f, Color.WHITE, bold = true)
        subtitleView = OverlayUi.text(context, "", 12f, 0xFF94A3B8.toInt()).apply {
            setPadding(0, dp(3f), 0, 0)
        }
        titles.addView(titleView)
        titles.addView(subtitleView)

        val close = OverlayUi.text(context, "✕", 16f, 0xFFCBD5E1.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            val size = dp(30f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            setOnClickListener { hide() }
        }

        // 面板会挡住题目，按住标题栏就能挪走；把手本身给个提示
        row.addView(OverlayUi.text(context, "⋮⋮", 13f, 0xFF64748B.toInt()).apply {
            setPadding(0, 0, dp(8f), 0)
        })
        row.addView(titles)
        row.addView(OverlayUi.spacer(context))
        row.addView(close)
        row.setOnTouchListener(PanelDragListener())
        return row
    }

    /**
     * 底部拖拽把手：中间一根小横条，上下拖动改面板高度。
     *
     * 面板原本是固定高度（屏幕的 66%）+ 固定在 12% 的位置，拍到答案长一点的题就挡住半屏，
     * 用户只能「收起」再重拍。现在高度可以拖，位置可以挪，够用的就缩小放到角落里。
     */
    private fun buildResizeHandle(): View {
        val wrapper = OverlayUi.horizontal(context).apply {
            gravity = Gravity.CENTER
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(20f),
            )
        }
        wrapper.addView(
            View(context).apply {
                background = OverlayUi.rounded(0x66FFFFFF, dp(2f).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(46f), dp(4f))
            },
        )
        wrapper.setOnTouchListener(ResizeListener())
        return wrapper
    }

    /** 按住标题栏拖动整个面板。 */
    private inner class PanelDragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val layout = params ?: return false
            val panel = root ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val metrics = context.resources.displayMetrics
                    // 至少留一角在屏幕里，否则用户再也抓不回这个面板
                    val keep = dp(56f)
                    layout.x = (startX + (event.rawX - downRawX)).roundToInt()
                        .coerceIn(keep - layout.width, metrics.widthPixels - keep)
                    layout.y = (startY + (event.rawY - downRawY)).roundToInt()
                        .coerceIn(0, metrics.heightPixels - keep)
                    runCatching { windowManager.updateViewLayout(panel, layout) }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    /** 上下拖动改高度。 */
    private inner class ResizeListener : View.OnTouchListener {
        private var downRawY = 0f
        private var startHeight = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val layout = params ?: return false
            val panel = root ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startHeight = layout.height
                    // 用户自己动过高度，以后按内容自动改就是跟他打架了
                    userResized = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val metrics = context.resources.displayMetrics
                    layout.height = (startHeight + (event.rawY - downRawY)).roundToInt()
                        .coerceIn(dp(150f), metrics.heightPixels - dp(16f))
                    runCatching { windowManager.updateViewLayout(panel, layout) }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    /**
     * 动作区。**分两行**，不是为了好看：
     * 「免费解析」「深度解析」「存入题库」都是四个汉字，加上「重拍」和「收起」
     * 挤在一行要 376dp，而卡片本身只有 357dp —— 五个按钮一行是排不下的。
     * 两个 AI 按钮单独一行，剩下的按老样子靠左靠右。
     */
    private fun buildActions(): View {
        val box = OverlayUi.vertical(context)

        val ai = OverlayUi.horizontal(context).apply {
            visibility = View.GONE
        }
        aiRow = ai

        val free = OverlayUi.button(context, "免费解析", 0xFF2F3646.toInt())
        free.setOnClickListener { onAiFree?.invoke() }
        free.visibility = View.GONE
        aiFreeButton = free
        ai.addView(free)

        val paid = OverlayUi.button(context, "深度解析", 0xFF1F6FEB.toInt())
        paid.setOnClickListener { onAiPaid?.invoke() }
        paid.visibility = View.GONE
        aiPaidButton = paid
        ai.addView(paid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8f) })

        box.addView(ai)

        val row = OverlayUi.horizontal(context).apply {
            setPadding(0, dp(10f), 0, 0)
        }
        // 「重拍」和「存入题库」都只在拍题模式下存在：搜题没有图可重拍、也没有图可存
        if (!searchMode) {
            val retry = OverlayUi.button(context, "重拍", 0xFF2F3646.toInt())
            retry.setOnClickListener { onRetry?.invoke() }
            retryButton = retry
            row.addView(retry)
        }

        // 只在拍题模式下建出来（搜题没有图可存），但要等 spacer 之后才 addView，
        // 所以先留着引用，顺序在下面统一排
        // 「收录」取代了原来的「存入题库」：那个只存题干，答案和解析都是空的，
        // 收进去还得回主界面补 —— 而答案和解析恰恰是这个题库最有价值的部分。
        val collect = if (searchMode) {
            null
        } else {
            OverlayUi.button(context, "收录", 0xFF2F3646.toInt()).apply {
                setOnClickListener { onCollect?.invoke() }
            }.also { collectButton = it }
        }

        val close = OverlayUi.button(context, "收起", 0xFF1F6FEB.toInt())
        close.setOnClickListener {
            // 搜题模式下「收起」是折回那条输入框（题干留着，可以改两个字再搜），
            // 拍题模式下才是把整块收掉 —— 拍题没有「只留一条输入框」这个形态。
            val collapse = onCollapse
            if (collapse != null) {
                collapse()
            } else {
                hide()
                onClose?.invoke()
            }
        }
        row.addView(OverlayUi.spacer(context))
        collect?.let { row.addView(it) }
        row.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8f) })
        box.addView(row)
        return box
    }

    private fun sectionLabel(text: String): TextView =
        OverlayUi.text(context, text, 12f, 0xFF7DD3FC.toInt(), bold = true).apply {
            setPadding(0, dp(10f), 0, dp(4f))
            visibility = View.GONE
        }

    private fun bodyText(color: Int = 0xFFE5E7EB.toInt(), italic: Boolean = false): TextView =
        OverlayUi.text(context, "", 14f, color).apply {
            movementMethod = ScrollingMovementMethod()
            setLineSpacing(dp(4f).toFloat(), 1f)
            if (italic) setTypeface(typeface, Typeface.ITALIC)
            visibility = View.GONE
            // 长答案允许用户长按选中复制（面板本身不可聚焦，但 TextView 自己可以）
            setTextIsSelectable(true)
        }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
            .roundToInt()

    private companion object {
        /** 搜题那条输入行的固定高度。 */
        const val SEARCH_ROW_DP = 46f

        /** 搜题收起时整张卡片的高度 = 上下内边距 + 输入行。 */
        const val SEARCH_BAR_DP = 64f

        /** 低于这个相似度的候选只当参考，不当结论。 */
        const val LOW_CONFIDENCE = 0.60

        /** 卡片再小也得放得下标题、一行状态和那两个按钮。 */
        const val MIN_PANEL_DP = 150f
    }
}
