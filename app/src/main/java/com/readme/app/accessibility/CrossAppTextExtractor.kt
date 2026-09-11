package com.readme.app.accessibility

/**
 * Extracts visible, read-only textual content from active accessibility trees into immutable [CrossAppTextSnapshot]s.
 *
 * Enforces:
 * - Deterministic DFS reading order
 * - Duplicate parent/child text suppression
 * - Exclusion of password, PIN, and sensitive credential fields
 * - Exclusion of ReadMe's own package (self-filter)
 * - Conservative content-description fallback (never blindly concatenating text + contentDescription)
 * - Safe upper bounds on visited nodes, block counts, and text size
 * - Safe node recycling
 */
object CrossAppTextExtractor {

    const val DEFAULT_MAX_NODES = 500
    const val DEFAULT_MAX_BLOCKS = 200
    const val DEFAULT_MAX_TEXT_LENGTH = 100_000
    const val README_PACKAGE = "com.readme.app"

    fun extract(
        root: AccessibleNode?,
        sourcePackageName: String? = null,
        sourceWindowId: Int? = null,
        generation: Long = 0L,
        title: String? = null,
        maxNodes: Int = DEFAULT_MAX_NODES,
        maxBlocks: Int = DEFAULT_MAX_BLOCKS,
        maxTextLength: Int = DEFAULT_MAX_TEXT_LENGTH
    ): CrossAppTextSnapshot? {
        if (root == null) return null

        val pkgName = (sourcePackageName ?: root.packageName?.toString() ?: "").trim()
        if (pkgName.isBlank() || pkgName == README_PACKAGE) {
            return null
        }

        var visitedNodes = 0
        var totalLength = 0
        val blocks = mutableListOf<CrossAppTextBlock>()
        var orderCounter = 0

        fun traverse(node: AccessibleNode) {
            if (visitedNodes >= maxNodes || blocks.size >= maxBlocks || totalLength >= maxTextLength) {
                return
            }
            visitedNodes++

            // Security check: skip password fields and sensitive classes
            if (node.isPassword) return
            val className = node.className?.toString() ?: ""
            if (className.contains("password", ignoreCase = true) || className.contains("pin", ignoreCase = true)) {
                return
            }

            // Skip invisible nodes when reported
            if (!node.isVisibleToUser) return

            // Skip ReadMe self nodes
            val nodePkg = node.packageName?.toString()
            if (nodePkg != null && nodePkg == README_PACKAGE) return

            val directText = extractDirectText(node)

            // Inspect children in order
            val childCount = node.childCount
            val childNodes = mutableListOf<AccessibleNode>()
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    childNodes.add(child)
                }
            }

            try {
                val childBlocks = mutableListOf<CrossAppTextBlock>()
                for (child in childNodes) {
                    if (visitedNodes >= maxNodes || blocks.size >= maxBlocks || totalLength >= maxTextLength) {
                        break
                    }
                    val childStartIndex = blocks.size
                    traverse(child)
                    if (blocks.size > childStartIndex) {
                        for (idx in childStartIndex until blocks.size) {
                            childBlocks.add(blocks[idx])
                        }
                    }
                }

                if (directText != null) {
                    // Check duplicate text against children
                    val duplicateWithChild = childBlocks.any { it.text.equals(directText, ignoreCase = true) } ||
                            (childBlocks.isNotEmpty() && childBlocks.joinToString(" ") { it.text }.equals(directText, ignoreCase = true))

                    if (!duplicateWithChild) {
                        // Avoid consecutive duplicates
                        val isConsecutiveDuplicate = blocks.isNotEmpty() &&
                                blocks.last().text.equals(directText, ignoreCase = true)

                        if (!isConsecutiveDuplicate && blocks.size < maxBlocks && totalLength + directText.length <= maxTextLength) {
                            val role = if (node.isHeading) CrossAppTextRole.HEADING else CrossAppTextRole.TEXT
                            // If directText is from this parent and didn't duplicate children, insert before children in blocks
                            val insertIndex = blocks.size - childBlocks.size
                            blocks.add(
                                insertIndex,
                                CrossAppTextBlock(
                                    text = directText,
                                    order = orderCounter++,
                                    role = role
                                )
                            )
                            totalLength += directText.length
                        }
                    }
                }
            } finally {
                for (child in childNodes) {
                    child.recycle()
                }
            }
        }

        traverse(root)

        // Re-index orders deterministically
        val finalBlocks = blocks.mapIndexed { index, block ->
            block.copy(order = index)
        }

        return CrossAppTextSnapshot(
            sourcePackageName = pkgName,
            sourceWindowId = sourceWindowId,
            capturedAt = System.currentTimeMillis(),
            generation = generation,
            title = title,
            blocks = finalBlocks
        )
    }

    private fun extractDirectText(node: AccessibleNode): String? {
        val rawText = node.text?.toString()?.trim()
        if (!rawText.isNullOrBlank()) {
            if (isMaskedSensitiveContent(rawText)) return null
            return normalizeWhitespace(rawText)
        }

        val rawDesc = node.contentDescription?.toString()?.trim()
        if (!rawDesc.isNullOrBlank()) {
            if (isMaskedSensitiveContent(rawDesc)) return null
            return normalizeWhitespace(rawDesc)
        }

        return null
    }

    private fun isMaskedSensitiveContent(content: String): Boolean {
        if (content.length in 1..64 && content.all { it == '•' || it == '*' || it == '●' }) {
            return true
        }
        return false
    }

    fun normalizeWhitespace(raw: String): String {
        return raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \t]+"), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }
}
