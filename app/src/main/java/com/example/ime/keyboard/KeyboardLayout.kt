package com.example.ime.keyboard

import android.graphics.RectF

enum class KeyboardMode {
    CHARACTERS,
    SYMBOLS_1,
    SYMBOLS_2,
    NUMPAD
}

enum class ShiftState {
    OFF,
    ON,
    CAPS_LOCK
}

class KeyboardLayout {

    var mode: KeyboardMode = KeyboardMode.CHARACTERS
    var shiftState: ShiftState = ShiftState.OFF

    val keys = mutableListOf<KeyData>()
    val toolbarKeys = mutableListOf<KeyData>()

    var suggestions: List<String> = emptyList()
    var spaceLabel: String = "EN"

    var isToolbarExpanded: Boolean = false
    var isIncognitoActive: Boolean = false
    var pinnedTools: List<com.example.ime.toolbar.ToolbarTool> = listOf(
        com.example.ime.toolbar.ToolbarTool.SELECT_WORD,
        com.example.ime.toolbar.ToolbarTool.COPY,
        com.example.ime.toolbar.ToolbarTool.PASTE
    )
    var expandedTools: List<com.example.ime.toolbar.ToolbarTool> = listOf(
        com.example.ime.toolbar.ToolbarTool.INCOGNITO,
        com.example.ime.toolbar.ToolbarTool.UNDO,
        com.example.ime.toolbar.ToolbarTool.REDO,
        com.example.ime.toolbar.ToolbarTool.SELECT_WORD,
        com.example.ime.toolbar.ToolbarTool.SELECT_ALL,
        com.example.ime.toolbar.ToolbarTool.COPY,
        com.example.ime.toolbar.ToolbarTool.PASTE,
        com.example.ime.toolbar.ToolbarTool.UP,
        com.example.ime.toolbar.ToolbarTool.DOWN,
        com.example.ime.toolbar.ToolbarTool.VOICE,
        com.example.ime.toolbar.ToolbarTool.PROMPT_LIST,
        com.example.ime.toolbar.ToolbarTool.SECURITY_VAULT,
        com.example.ime.toolbar.ToolbarTool.DESKTOP_SHORTCUTS,
        com.example.ime.toolbar.ToolbarTool.SETTINGS
    )
    var hidePinnedWhenExpanded: Boolean = false
    var toolbarScrollOffset: Float = 0f
    var maxToolbarScrollOffset: Float = 0f
    val toolbarScrollBounds = RectF()

    private fun buildMoreKeys(hint: String?, baseMore: List<String>): List<String> {
        if (hint.isNullOrEmpty() || hint == "…") return baseMore
        val result = mutableListOf<String>()
        result.add(hint)
        for (item in baseMore) {
            if (item != hint) {
                result.add(item)
            }
        }
        return result
    }

    fun buildLayout(width: Float, height: Float, theme: KeyboardTheme, density: Float, bottomInsetPx: Float = 0f) {
        keys.clear()
        toolbarKeys.clear()
        if (width <= 0 || height <= 0) return

        val horizontalGapPx = theme.horizontalGapDp * density
        val verticalGapPx = theme.verticalGapDp * density
        val paddingHorizontalPx = 4f * density
        val paddingVerticalPx = 4f * density

        val availableWidth = width - (paddingHorizontalPx * 2)

        // 1. Build Top Toolbar
        val toolbarHeightPx = theme.toolbarHeightDp * density
        buildToolbar(paddingHorizontalPx, paddingVerticalPx, availableWidth, toolbarHeightPx, density)

        // 2. Build Keyboard Grid
        val keyboardStartY = paddingVerticalPx + toolbarHeightPx + (2f * density)
        val availableKeyboardHeight = height - keyboardStartY - paddingVerticalPx - bottomInsetPx

        val rowDefinitions = getRowDefinitions(mode, shiftState)
        val rowCount = rowDefinitions.size
        val totalVerticalGaps = (rowCount - 1) * verticalGapPx
        val keyHeight = (availableKeyboardHeight - totalVerticalGaps) / rowCount

        var currentY = keyboardStartY

        if (mode == KeyboardMode.CHARACTERS) {
            val colWidth = (availableWidth - 9f * horizontalGapPx) / 10f

            for (rowIndex in rowDefinitions.indices) {
                val row = rowDefinitions[rowIndex]
                var currentX = paddingHorizontalPx

                when (rowIndex) {
                    0, 1 -> {
                        // Row 0 & Row 1: 10 equal columns spanning full width
                        for (key in row) {
                            key.bounds.set(currentX, currentY, currentX + colWidth, currentY + keyHeight)
                            keys.add(key)
                            currentX += colWidth + horizontalGapPx
                        }
                    }
                    2 -> {
                        // Row 2: Staggered with half-key spacer on left and right (a-l)
                        currentX += 0.5f * (colWidth + horizontalGapPx)
                        for (key in row) {
                            key.bounds.set(currentX, currentY, currentX + colWidth, currentY + keyHeight)
                            keys.add(key)
                            currentX += colWidth + horizontalGapPx
                        }
                    }
                    3 -> {
                        // Row 3: Shift (1.5x), 7 character keys (1.0x), Delete (1.5x)
                        val functionalWidth = 1.5f * colWidth + 0.5f * horizontalGapPx
                        for (keyIndex in row.indices) {
                            val key = row[keyIndex]
                            val kWidth = if (keyIndex == 0 || keyIndex == row.lastIndex) functionalWidth else colWidth
                            key.bounds.set(currentX, currentY, currentX + kWidth, currentY + keyHeight)
                            keys.add(key)
                            currentX += kWidth + horizontalGapPx
                        }
                    }
                    4 -> {
                        // Row 4: ?123 (1.5x), , (1.0x), Space (5.0x), . (1.0x), Enter (1.5x)
                        val functionalWidth = 1.5f * colWidth + 0.5f * horizontalGapPx
                        val spaceWidth = 5f * colWidth + 4f * horizontalGapPx
                        for (key in row) {
                            val kWidth = when (key.type) {
                                KeyType.SYMBOLS_TOGGLE, KeyType.ENTER -> functionalWidth
                                KeyType.SPACE -> spaceWidth
                                else -> colWidth
                            }
                            key.bounds.set(currentX, currentY, currentX + kWidth, currentY + keyHeight)
                            keys.add(key)
                            currentX += kWidth + horizontalGapPx
                        }
                    }
                }
                currentY += keyHeight + verticalGapPx
            }
        } else {
            for (row in rowDefinitions) {
                val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
                val totalGaps = (row.size - 1) * horizontalGapPx
                val widthForKeys = availableWidth - totalGaps

                var currentX = paddingHorizontalPx

                for (key in row) {
                    val keyWidth = (key.weight / totalWeight) * widthForKeys
                    key.bounds.set(
                        currentX,
                        currentY,
                        currentX + keyWidth,
                        currentY + keyHeight
                    )
                    keys.add(key)
                    currentX += keyWidth + horizontalGapPx
                }

                currentY += keyHeight + verticalGapPx
            }
        }
    }

    private fun buildToolbar(startX: Float, startY: Float, totalWidth: Float, height: Float, density: Float) {
        val anchorBtnWidth = 36f * density
        val spacing = 4f * density
        var currentX = startX

        // 1. Left anchor button: Expand/Collapse Chevron OR Incognito Badge
        val anchorLabel = if (isIncognitoActive) "🕶️" else (if (isToolbarExpanded) "‹" else "›")
        val anchorKey = KeyData(
            code = -101,
            label = anchorLabel,
            type = KeyType.ACTION_EXPAND,
            weight = 1f,
            bounds = RectF(currentX, startY, currentX + anchorBtnWidth, startY + height)
        )
        toolbarKeys.add(anchorKey)
        currentX += anchorBtnWidth + spacing

        // Pinned tools docked on right edge
        val pinnedToRender = pinnedTools
        val pinnedBtnWidth = 32f * density
        val showPinned = pinnedToRender.isNotEmpty() && (!isToolbarExpanded || !hidePinnedWhenExpanded)
        val rightMargin = 4f * density
        val rightPinnedTotalWidth = if (showPinned) {
            (pinnedBtnWidth * pinnedToRender.size) + (spacing * (pinnedToRender.size - 1))
        } else 0f
        val rightPinnedStartX = (totalWidth + startX) - rightPinnedTotalWidth - rightMargin
        val middleAreaWidth = (rightPinnedStartX - currentX - spacing).coerceAtLeast(0f)

        if (isToolbarExpanded) {
            // EXPANDED TOOLBAR: Ensure touch targets are at least 42dp wide with horizontal scroll
            val toolsToRender = expandedTools.ifEmpty {
                com.example.ime.toolbar.ToolbarTool.values().filter { it.isDefaultExpanded }
            }

            val minToolWidth = 42f * density
            val naturalWidth = (middleAreaWidth - (spacing * (toolsToRender.size - 1).coerceAtLeast(0))) / toolsToRender.size.coerceAtLeast(1)
            val toolBtnWidth = naturalWidth.coerceAtLeast(minToolWidth)

            val totalContentWidth = (toolBtnWidth * toolsToRender.size) + (spacing * (toolsToRender.size - 1).coerceAtLeast(0))
            maxToolbarScrollOffset = (totalContentWidth - middleAreaWidth).coerceAtLeast(0f)
            toolbarScrollOffset = toolbarScrollOffset.coerceIn(0f, maxToolbarScrollOffset)

            toolbarScrollBounds.set(currentX, startY, currentX + middleAreaWidth, startY + height)

            for ((idx, tool) in toolsToRender.withIndex()) {
                val itemLeft = currentX + (idx * (toolBtnWidth + spacing))
                val toolKey = KeyData(
                    code = -300 - idx,
                    label = "",
                    type = KeyType.TOOLBAR_TOOL,
                    weight = 1f,
                    bounds = RectF(itemLeft, startY, itemLeft + toolBtnWidth, startY + height)
                ).apply {
                    this.tool = tool
                }
                toolbarKeys.add(toolKey)
            }
        } else {
            toolbarScrollOffset = 0f
            maxToolbarScrollOffset = 0f
            toolbarScrollBounds.set(0f, 0f, 0f, 0f)

            // COLLAPSED TOOLBAR: Suggestions in middle area
            if (suggestions.isNotEmpty()) {
                val eachSugWidth = (middleAreaWidth - (spacing * (suggestions.size - 1))) / suggestions.size
                for ((idx, sug) in suggestions.withIndex()) {
                    val sugKey = KeyData(
                        code = -200 - idx,
                        label = sug,
                        type = KeyType.SUGGESTION,
                        weight = 1f,
                        bounds = RectF(currentX, startY, currentX + eachSugWidth, startY + height)
                    )
                    toolbarKeys.add(sugKey)
                    currentX += eachSugWidth + spacing
                }
            }
        }

        // Dock pinned tools on right edge in both collapsed and expanded states
        if (showPinned) {
            var pinnedX = rightPinnedStartX
            for ((idx, tool) in pinnedToRender.withIndex()) {
                val toolKey = KeyData(
                    code = -400 - idx,
                    label = "",
                    type = KeyType.TOOLBAR_TOOL,
                    weight = 1f,
                    bounds = RectF(pinnedX, startY, pinnedX + pinnedBtnWidth, startY + height)
                ).apply {
                    this.tool = tool
                }
                toolbarKeys.add(toolKey)
                pinnedX += pinnedBtnWidth + spacing
            }
        }
    }

    private fun getRowDefinitions(mode: KeyboardMode, shiftState: ShiftState): List<List<KeyData>> {
        return when (mode) {
            KeyboardMode.CHARACTERS -> getQwerty5Rows(shiftState)
            KeyboardMode.SYMBOLS_1 -> getSymbols1Rows()
            KeyboardMode.SYMBOLS_2 -> getSymbols2Rows()
            KeyboardMode.NUMPAD -> getNumpadRows()
        }
    }

    private fun getQwerty5Rows(shiftState: ShiftState): List<List<KeyData>> {
        val isCaps = shiftState != ShiftState.OFF

        // Row 0: Numbers with superscripts & fractions
        val numChars = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val numHints = listOf("¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹", "⁰")
        val numMore = listOf(
            listOf("1", "¹", "½", "⅓", "¼", "⅛"),
            listOf("2", "²", "⅔", "⅖"),
            listOf("3", "³", "¾", "⅜"),
            listOf("4", "⁴", "⅘"),
            listOf("5", "⁵", "⅝"),
            listOf("6", "⁶"),
            listOf("7", "⁷", "⅞"),
            listOf("8", "⁸"),
            listOf("9", "⁹"),
            listOf("0", "⁰", "ⁿ", "∅")
        )

        val row0 = numChars.mapIndexed { idx, ch ->
            KeyData(
                code = ch[0].code,
                label = ch,
                hintLabel = numHints[idx],
                moreKeys = buildMoreKeys(numHints[idx], numMore[idx]),
                type = KeyType.CHARACTER,
                weight = 1.0f
            )
        }

        // Row 1: Q-P with corner hint symbols matching Screenshot 1
        val r1Chars = if (isCaps) listOf("Q","W","E","R","T","Y","U","I","O","P") else listOf("q","w","e","r","t","y","u","i","o","p")
        val r1Hints = listOf("%", "/", "|", "=", "[", "]", "*", "!", "-", ";")
        val r1More = if (isCaps) {
            listOf(
                listOf("Q"),
                listOf("W"),
                listOf("E", "É", "È", "Ê", "Ë", "Ē", "Ę"),
                listOf("R"),
                listOf("T", "Þ"),
                listOf("Y", "Ý", "Ÿ"),
                listOf("U", "Ú", "Ù", "Û", "Ü", "Ū", "Ů"),
                listOf("I", "Í", "Ì", "Î", "Ï", "Ī", "Į"),
                listOf("O", "Ó", "Ò", "Ô", "Ö", "Õ", "Ō", "Œ", "Ø"),
                listOf("P")
            )
        } else {
            listOf(
                listOf("q"),
                listOf("w"),
                listOf("e", "é", "è", "ê", "ë", "ē", "ę"),
                listOf("r"),
                listOf("t", "þ"),
                listOf("y", "ý", "ÿ"),
                listOf("u", "ú", "ù", "û", "ü", "ū", "ů"),
                listOf("i", "í", "ì", "î", "ï", "ī", "į"),
                listOf("o", "ó", "ò", "ô", "ö", "õ", "ō", "œ", "ø"),
                listOf("p")
            )
        }

        val row1 = r1Chars.mapIndexed { idx, ch ->
            KeyData(
                code = ch[0].code,
                label = ch,
                hintLabel = r1Hints[idx],
                moreKeys = buildMoreKeys(r1Hints[idx], r1More[idx]),
                type = KeyType.CHARACTER,
                weight = 1.0f
            )
        }

        // Row 2: A-L with corner hint symbols
        val r2Chars = if (isCaps) listOf("A","S","D","F","G","H","J","K","L") else listOf("a","s","d","f","g","h","j","k","l")
        val r2Hints = listOf("@", "#", "₹", "_", "&", "-", "+", "(", ")")
        val r2More = if (isCaps) {
            listOf(
                listOf("A", "Á", "À", "Â", "Ä", "Ã", "Å", "Ā", "Æ"),
                listOf("S", "ß", "Ś", "Š", "$"),
                listOf("D", "Ð", "Đ"),
                listOf("F"),
                listOf("G"),
                listOf("H"),
                listOf("J"),
                listOf("K"),
                listOf("L", "Ł")
            )
        } else {
            listOf(
                listOf("a", "á", "à", "â", "ä", "ã", "å", "ā", "æ"),
                listOf("s", "ß", "ś", "š", "$"),
                listOf("d", "ð", "đ"),
                listOf("f"),
                listOf("g"),
                listOf("h"),
                listOf("j"),
                listOf("k"),
                listOf("l", "ł")
            )
        }

        val row2 = r2Chars.mapIndexed { idx, ch ->
            KeyData(
                code = ch[0].code,
                label = ch,
                hintLabel = r2Hints[idx],
                moreKeys = buildMoreKeys(r2Hints[idx], r2More[idx]),
                type = KeyType.CHARACTER,
                weight = 1.0f
            )
        }

        // Row 3: Shift, Z-M, Delete
        val shiftLabel = when (shiftState) {
            ShiftState.CAPS_LOCK -> "⇪"
            ShiftState.ON -> "▲"
            ShiftState.OFF -> "⇧"
        }

        val r3Chars = if (isCaps) listOf("Z","X","C","V","B","N","M") else listOf("z","x","c","v","b","n","m")
        val r3Hints = listOf("*", "\"", "'", ":", ";", "!", "?")
        val r3More = if (isCaps) {
            listOf(
                listOf("Z", "Ź", "Ż", "Ž"),
                listOf("X"),
                listOf("C", "Ç", "Ć", "Č"),
                listOf("V"),
                listOf("B"),
                listOf("N", "Ñ", "Ń"),
                listOf("M")
            )
        } else {
            listOf(
                listOf("z", "ź", "ż", "ž"),
                listOf("x"),
                listOf("c", "ç", "ć", "č"),
                listOf("v"),
                listOf("b"),
                listOf("n", "ñ", "ń"),
                listOf("m")
            )
        }

        val row3 = mutableListOf<KeyData>()
        row3.add(KeyData(code = -1, label = shiftLabel, type = KeyType.SHIFT, weight = 1.4f))
        for (i in r3Chars.indices) {
            row3.add(
                KeyData(
                    code = r3Chars[i][0].code,
                    label = r3Chars[i],
                    hintLabel = r3Hints[i],
                    moreKeys = buildMoreKeys(r3Hints[i], r3More[i]),
                    type = KeyType.CHARACTER,
                    weight = 1.0f
                )
            )
        }
        row3.add(KeyData(code = -2, label = "⌫", type = KeyType.DELETE, weight = 1.4f))

        // Row 4: ?123, Comma, Space, Period, Enter
        val row4 = listOf(
            KeyData(code = -3, label = "?123", type = KeyType.SYMBOLS_TOGGLE, weight = 1.4f),
            KeyData(
                code = ','.code,
                label = ",",
                hintLabel = "…",
                moreKeys = listOf(",", ";", ":", "…"),
                type = KeyType.COMMA,
                weight = 1.0f
            ),
            KeyData(code = 32, label = spaceLabel, hintLabel = null, type = KeyType.SPACE, weight = 4.6f),
            KeyData(
                code = '.'.code,
                label = ".",
                hintLabel = "…",
                moreKeys = listOf(".", "…", "!", "?", "-", "_"),
                type = KeyType.PERIOD,
                weight = 1.0f
            ),
            KeyData(code = -4, label = "↵", hintLabel = null, type = KeyType.ENTER, weight = 1.6f)
        )

        return listOf(row0, row1, row2, row3, row4)
    }

    // Symbols Page 1 (Matching Screenshot 4)
    private fun getSymbols1Rows(): List<List<KeyData>> {
        // Row 0: 1 2 3 4 5 6 7 8 9 0
        val r0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }
        // Row 1: % / | = [ ] * ! - ; " (11 keys)
        val r1 = listOf("%", "/", "|", "=", "[", "]", "*", "!", "-", ";", "\"").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }
        // Row 2: @ # ₹ _ & - + ( ) { } (11 keys)
        val r2 = listOf("@", "#", "₹", "_", "&", "-", "+", "(", ")", "{", "}").map {
            val more = if (it == "₹") listOf("₹", "$", "€", "£", "¥", "¢") else listOf(it)
            KeyData(code = it[0].code, label = it, moreKeys = more, type = KeyType.CHARACTER, weight = 1.0f)
        }

        // Row 3: =\< * " ' : ; ! ? : ⌫
        val row3 = mutableListOf<KeyData>()
        row3.add(KeyData(code = -5, label = "=\\<", type = KeyType.SYMBOLS_MORE_TOGGLE, weight = 1.4f))
        val r3 = listOf("*", "\"", "'", ":", ";", "!", "?", ":")
        for (ch in r3) {
            row3.add(KeyData(code = ch[0].code, label = ch, type = KeyType.CHARACTER, weight = 1.0f))
        }
        row3.add(KeyData(code = -2, label = "⌫", type = KeyType.DELETE, weight = 1.4f))

        // Row 4: ABC , 12/34 Space . ↵
        val row4 = listOf(
            KeyData(code = -3, label = "ABC", type = KeyType.SYMBOLS_TOGGLE, weight = 1.3f),
            KeyData(code = ','.code, label = ",", hintLabel = "…", type = KeyType.COMMA, weight = 0.9f),
            KeyData(code = -7, label = "12\n34", type = KeyType.NUMPAD_TOGGLE, weight = 0.9f),
            KeyData(code = 32, label = "", hintLabel = null, type = KeyType.SPACE, weight = 4.0f),
            KeyData(code = '.'.code, label = ".", type = KeyType.PERIOD, weight = 0.9f),
            KeyData(code = -4, label = "↵", hintLabel = null, type = KeyType.ENTER, weight = 1.5f)
        )

        return listOf(r0, r1, r2, row3, row4)
    }

    // Symbols Page 2 (Matching Screenshot 3)
    private fun getSymbols2Rows(): List<List<KeyData>> {
        // Row 0: 1 2 3 4 5 6 7 8 9 0
        val r0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }
        // Row 1: ~ ` | • √ π ÷ × ¶ Δ
        val r1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "Δ").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }
        // Row 2: £ € $ ¢ ^ ° = { }
        val r2 = listOf("£", "€", "$", "¢", "^", "°", "=", "{", "}").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }

        // Row 3: ?123 \ © ® ™ % [ ] ⌫
        val row3 = mutableListOf<KeyData>()
        row3.add(KeyData(code = -6, label = "?123", type = KeyType.SYMBOLS_MORE_TOGGLE, weight = 1.4f))
        val r3 = listOf("\\", "©", "®", "™", "%", "[", "]")
        for (ch in r3) {
            row3.add(KeyData(code = ch[0].code, label = ch, type = KeyType.CHARACTER, weight = 1.0f))
        }
        row3.add(KeyData(code = -2, label = "⌫", type = KeyType.DELETE, weight = 1.4f))

        // Row 4: ABC < Space > ↵
        val row4 = listOf(
            KeyData(code = -3, label = "ABC", type = KeyType.SYMBOLS_TOGGLE, weight = 1.4f),
            KeyData(code = '<'.code, label = "<", type = KeyType.CHARACTER, weight = 1.0f),
            KeyData(code = 32, label = "", hintLabel = null, type = KeyType.SPACE, weight = 4.6f),
            KeyData(code = '>'.code, label = ">", type = KeyType.CHARACTER, weight = 1.0f),
            KeyData(code = -4, label = "↵", hintLabel = null, type = KeyType.ENTER, weight = 1.6f)
        )

        return listOf(r0, r1, r2, row3, row4)
    }

    // Dedicated 3x4 Numpad / Calculator Grid (Matching Screenshot 2)
    private fun getNumpadRows(): List<List<KeyData>> {
        // Row 0: +( , 1 , 2 , 3 , %₹
        val row0 = listOf(
            KeyData(code = '+'.code, label = "+", hintLabel = "(", moreKeys = listOf("+", "("), type = KeyType.CHARACTER, weight = 1.2f),
            KeyData(code = '1'.code, label = "1", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '2'.code, label = "2", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '3'.code, label = "3", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '%'.code, label = "%", hintLabel = "₹", moreKeys = listOf("%", "₹", "$"), type = KeyType.CHARACTER, weight = 1.2f)
        )

        // Row 1: -) , 4 , 5 , 6 , _
        val row1 = listOf(
            KeyData(code = '-'.code, label = "-", hintLabel = ")", moreKeys = listOf("-", ")"), type = KeyType.CHARACTER, weight = 1.2f),
            KeyData(code = '4'.code, label = "4", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '5'.code, label = "5", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '6'.code, label = "6", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '_'.code, label = "_", type = KeyType.CHARACTER, weight = 1.2f)
        )

        // Row 2: */ , 7 , 8 , 9 , ⌫
        val row2 = listOf(
            KeyData(code = '*'.code, label = "*", hintLabel = "/", moreKeys = listOf("*", "/"), type = KeyType.CHARACTER, weight = 1.2f),
            KeyData(code = '7'.code, label = "7", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '8'.code, label = "8", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '9'.code, label = "9", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = -2, label = "⌫", type = KeyType.DELETE, weight = 1.2f)
        )

        // Row 3: ABC , , , 0 , ?123 , =# , :: , ↵
        val row3 = listOf(
            KeyData(code = -3, label = "ABC", type = KeyType.SYMBOLS_TOGGLE, weight = 1.2f),
            KeyData(code = ','.code, label = ",", hintLabel = "…", type = KeyType.COMMA, weight = 1.0f),
            KeyData(code = -3, label = "?123", type = KeyType.SYMBOLS_TOGGLE, weight = 1.0f),
            KeyData(code = '0'.code, label = "0", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '='.code, label = "=", hintLabel = "#", moreKeys = listOf("=", "#"), type = KeyType.CHARACTER, weight = 1.0f),
            KeyData(code = ':'.code, label = ":", hintLabel = ":", type = KeyType.CHARACTER, weight = 0.8f),
            KeyData(code = -4, label = "↵", hintLabel = null, type = KeyType.ENTER, weight = 1.4f)
        )

        return listOf(row0, row1, row2, row3)
    }

    fun findKeyAt(x: Float, y: Float): KeyData? {
        val keyHit = keys.firstOrNull { it.bounds.contains(x, y) }
        if (keyHit != null) return keyHit

        // Toolbar hit testing
        val anchorHit = toolbarKeys.firstOrNull { it.type == KeyType.ACTION_EXPAND && it.bounds.contains(x, y) }
        if (anchorHit != null) return anchorHit

        if (isToolbarExpanded && toolbarScrollBounds.contains(x, y)) {
            val adjustedX = x + toolbarScrollOffset
            return toolbarKeys.firstOrNull { it.code in -300 downTo -399 && it.bounds.contains(adjustedX, y) }
        }

        return toolbarKeys.firstOrNull { it.bounds.contains(x, y) }
    }
}
