package com.readme.app.accessibility

import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.content.CrossAppDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestAccessibleNode(
    override val packageName: CharSequence? = "com.other.app",
    override val text: CharSequence? = null,
    override val contentDescription: CharSequence? = null,
    override val isPassword: Boolean = false,
    override val isVisibleToUser: Boolean = true,
    override val isHeading: Boolean = false,
    override val className: CharSequence? = "android.widget.TextView",
    private val children: List<AccessibleNode> = emptyList()
) : AccessibleNode {
    var recycled = false

    override val childCount: Int get() = children.size

    override fun getChild(index: Int): AccessibleNode? {
        return if (index in children.indices) children[index] else null
    }

    override fun recycle() {
        recycled = true
    }
}

class CrossAppTextAcquisitionTest {

    // 1. Empty accessibility snapshot handling
    @Test
    fun `extract with null root returns null`() {
        val snapshot = CrossAppTextExtractor.extract(null)
        assertNull(snapshot)
    }

    @Test
    fun `extract with blank root returns empty snapshot`() {
        val root = TestAccessibleNode(text = "   ")
        val snapshot = CrossAppTextExtractor.extract(root)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.isEmpty)
        assertEquals(0, snapshot.blocks.size)
    }

    // 2. Single text node extraction
    @Test
    fun `extract single text node produces single text block`() {
        val root = TestAccessibleNode(text = "Hello world from external app.")
        val snapshot = CrossAppTextExtractor.extract(root)
        assertNotNull(snapshot)
        assertFalse(snapshot!!.isEmpty)
        assertEquals(1, snapshot.blocks.size)
        assertEquals("Hello world from external app.", snapshot.blocks[0].text)
        assertEquals(CrossAppTextRole.TEXT, snapshot.blocks[0].role)
        assertEquals(0, snapshot.blocks[0].order)
    }

    // 3. Multiple text nodes DFS reading-order preservation
    @Test
    fun `extract multiple nodes preserves DFS reading order`() {
        val child1 = TestAccessibleNode(text = "First paragraph.")
        val child2 = TestAccessibleNode(
            text = null,
            children = listOf(
                TestAccessibleNode(text = "Nested child text.")
            )
        )
        val child3 = TestAccessibleNode(text = "Final paragraph.")
        val root = TestAccessibleNode(
            text = null,
            children = listOf(child1, child2, child3)
        )

        val snapshot = CrossAppTextExtractor.extract(root)
        assertNotNull(snapshot)
        val texts = snapshot!!.blocks.map { it.text }
        assertEquals(listOf("First paragraph.", "Nested child text.", "Final paragraph."), texts)
        assertEquals(listOf(0, 1, 2), snapshot.blocks.map { it.order })
    }

    // 4. Duplicate parent/child text suppression
    @Test
    fun `suppresses duplicate parent text identical to single child`() {
        val child = TestAccessibleNode(text = "Chapter One")
        val parent = TestAccessibleNode(
            text = "Chapter One",
            children = listOf(child)
        )

        val snapshot = CrossAppTextExtractor.extract(parent)
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.blocks.size)
        assertEquals("Chapter One", snapshot.blocks[0].text)
    }

    @Test
    fun `suppresses parent text identical to joined children`() {
        val child1 = TestAccessibleNode(text = "Hello")
        val child2 = TestAccessibleNode(text = "World")
        val parent = TestAccessibleNode(
            text = "Hello World",
            children = listOf(child1, child2)
        )

        val snapshot = CrossAppTextExtractor.extract(parent)
        assertNotNull(snapshot)
        assertEquals(listOf("Hello", "World"), snapshot!!.blocks.map { it.text })
    }

    // 5. Normal whitespace normalization
    @Test
    fun `whitespace normalization collapses repeated spaces and preserves punctuation`() {
        val raw = "Hello,   world!   How   are you?  \n  I'm fine.  "
        val normalized = CrossAppTextExtractor.normalizeWhitespace(raw)
        assertEquals("Hello, world! How are you?\nI'm fine.", normalized)
    }

    // 6. Content description fallback
    @Test
    fun `uses content description when text is missing`() {
        val node = TestAccessibleNode(text = null, contentDescription = "Accessible description text")
        val snapshot = CrossAppTextExtractor.extract(node)
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.blocks.size)
        assertEquals("Accessible description text", snapshot.blocks[0].text)
    }

    @Test
    fun `prefers text over content description and does not blindly concatenate`() {
        val node = TestAccessibleNode(
            text = "Authoritative text",
            contentDescription = "Descriptive text"
        )
        val snapshot = CrossAppTextExtractor.extract(node)
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.blocks.size)
        assertEquals("Authoritative text", snapshot.blocks[0].text)
    }

    // 7. Filtering rules
    @Test
    fun `skips invisible nodes`() {
        val hiddenNode = TestAccessibleNode(text = "Hidden secret", isVisibleToUser = false)
        val snapshot = CrossAppTextExtractor.extract(hiddenNode)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.isEmpty)
    }

    @Test
    fun `skips ReadMe own package self filter`() {
        val readmeNode = TestAccessibleNode(packageName = "com.readme.app", text = "ReadMe self UI")
        val snapshot = CrossAppTextExtractor.extract(readmeNode)
        assertNull(snapshot)
    }

    @Test
    fun `skips password fields`() {
        val passwordNode = TestAccessibleNode(
            text = "SuperSecretPassword123",
            isPassword = true
        )
        val snapshot = CrossAppTextExtractor.extract(passwordNode)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.isEmpty)
    }

    @Test
    fun `skips masked password text`() {
        val maskedNode = TestAccessibleNode(text = "••••••••")
        val snapshot = CrossAppTextExtractor.extract(maskedNode)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.isEmpty)
    }

    @Test
    fun `skips password class names`() {
        val pinNode = TestAccessibleNode(
            text = "1234",
            className = "com.bank.app.PinEntryView"
        )
        val snapshot = CrossAppTextExtractor.extract(pinNode)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.isEmpty)
    }

    // 8. Maximum bounds limits
    @Test
    fun `enforces maximum node limit`() {
        val deepChildren = mutableListOf<AccessibleNode>()
        for (i in 0 until 50) {
            deepChildren.add(TestAccessibleNode(text = "Item $i"))
        }
        val root = TestAccessibleNode(children = deepChildren)

        val snapshot = CrossAppTextExtractor.extract(root, maxNodes = 10)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.blocks.size <= 10)
    }

    @Test
    fun `enforces maximum text length limit`() {
        val child1 = TestAccessibleNode(text = "A".repeat(100))
        val child2 = TestAccessibleNode(text = "B".repeat(100))
        val root = TestAccessibleNode(children = listOf(child1, child2))

        val snapshot = CrossAppTextExtractor.extract(root, maxTextLength = 120)
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.blocks.size)
        assertEquals(100, snapshot.blocks[0].text.length)
    }

    // 9. CrossAppDocumentParser
    @Test
    fun `parser converts empty snapshot to empty ReadingDocument`() {
        val snapshot = CrossAppTextSnapshot(sourcePackageName = "com.test.empty")
        val doc = CrossAppDocumentParser.parse(snapshot)
        assertEquals(0, doc.allSegments().size)
        assertEquals(ReadingDocumentSourceType.OTHER, doc.metadata.sourceType)
    }

    @Test
    fun `parser converts single block to ReadingDocument with section and sentence segments`() {
        val snapshot = CrossAppTextSnapshot(
            sourcePackageName = "com.test.news",
            blocks = listOf(
                CrossAppTextBlock(text = "First sentence. Second sentence! Third sentence?", order = 0)
            )
        )
        val doc = CrossAppDocumentParser.parse(snapshot)
        assertEquals(1, doc.sections.size)
        val segments = doc.allSegments()
        assertEquals(3, segments.size)
        assertEquals("First sentence.", segments[0].text)
        assertEquals("Second sentence!", segments[1].text)
        assertEquals("Third sentence?", segments[2].text)
        assertTrue(segments[0].id.contains("_seg_0"))
        assertTrue(segments[1].id.contains("_seg_1"))
        assertEquals(ReadingDocumentSourceType.OTHER, doc.metadata.sourceType)
    }

    @Test
    fun `parser derives title from explicit title or formatted package name`() {
        val explicitSnapshot = CrossAppTextSnapshot(
            sourcePackageName = "com.news.reader",
            title = "Article Headline",
            blocks = listOf(CrossAppTextBlock(text = "Article text.", order = 0))
        )
        val explicitDoc = CrossAppDocumentParser.parse(explicitSnapshot)
        assertEquals("Article Headline", explicitDoc.title)

        val packageSnapshot = CrossAppTextSnapshot(
            sourcePackageName = "org.wikipedia",
            title = null,
            blocks = listOf(CrossAppTextBlock(text = "Wikipedia entry.", order = 0))
        )
        val packageDoc = CrossAppDocumentParser.parse(packageSnapshot)
        assertEquals("Text from Wikipedia", packageDoc.title)
    }

    // 10. CrossAppAcquisitionCoordinator
    @Test
    fun `coordinator returns ServiceNotConnected when acquirer is null`() {
        val request = CrossAppAcquisitionRequest()
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(null, request)
        assertEquals(AcquisitionResult.ServiceNotConnected, result)
    }

    @Test
    fun `coordinator returns NoTextAvailable when snapshot is null or empty`() {
        val nullAcquirer = object : CrossAppTextAcquirer {
            override fun acquireCurrentText(): CrossAppTextSnapshot? = null
        }
        val emptyAcquirer = object : CrossAppTextAcquirer {
            override fun acquireCurrentText(): CrossAppTextSnapshot =
                CrossAppTextSnapshot(sourcePackageName = "com.empty.app")
        }

        assertEquals(
            AcquisitionResult.NoTextAvailable,
            CrossAppAcquisitionCoordinator.executeAcquisition(nullAcquirer, CrossAppAcquisitionRequest())
        )
        assertEquals(
            AcquisitionResult.NoTextAvailable,
            CrossAppAcquisitionCoordinator.executeAcquisition(emptyAcquirer, CrossAppAcquisitionRequest())
        )
    }

    @Test
    fun `coordinator returns ReadMeSelfIgnored when snapshot package is readme`() {
        val selfAcquirer = object : CrossAppTextAcquirer {
            override fun acquireCurrentText(): CrossAppTextSnapshot =
                CrossAppTextSnapshot(
                    sourcePackageName = "com.readme.app",
                    blocks = listOf(CrossAppTextBlock("Self text", 0))
                )
        }
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(selfAcquirer, CrossAppAcquisitionRequest())
        assertEquals(AcquisitionResult.ReadMeSelfIgnored, result)
    }

    @Test
    fun `coordinator returns StaleAppSwitch on package mismatch`() {
        val acquirer = object : CrossAppTextAcquirer {
            override fun acquireCurrentText(): CrossAppTextSnapshot =
                CrossAppTextSnapshot(
                    sourcePackageName = "com.other.app",
                    blocks = listOf(CrossAppTextBlock("Some text", 0))
                )
        }
        val request = CrossAppAcquisitionRequest(targetPackageName = "com.expected.app")
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(acquirer, request)
        assertTrue(result is AcquisitionResult.StaleAppSwitch)
        val stale = result as AcquisitionResult.StaleAppSwitch
        assertEquals("com.expected.app", stale.requestedPackage)
        assertEquals("com.other.app", stale.actualPackage)
    }

    @Test
    fun `coordinator returns StaleGeneration on older snapshot generation`() {
        val acquirer = object : CrossAppTextAcquirer {
            override fun acquireCurrentText(): CrossAppTextSnapshot =
                CrossAppTextSnapshot(
                    sourcePackageName = "com.target.app",
                    generation = 2L,
                    blocks = listOf(CrossAppTextBlock("Some text", 0))
                )
        }
        val request = CrossAppAcquisitionRequest(
            targetPackageName = "com.target.app",
            expectedGeneration = 5L
        )
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(acquirer, request)
        assertTrue(result is AcquisitionResult.StaleGeneration)
        val stale = result as AcquisitionResult.StaleGeneration
        assertEquals(5L, stale.expected)
        assertEquals(2L, stale.actual)
    }

    @Test
    fun `coordinator returns Success with parsed ReadingDocument when request is valid`() {
        val acquirer = object : CrossAppTextAcquirer {
            override fun acquireCurrentText(): CrossAppTextSnapshot =
                CrossAppTextSnapshot(
                    sourcePackageName = "com.target.app",
                    generation = 10L,
                    blocks = listOf(
                        CrossAppTextBlock(text = "Valid reading sentence one. Valid reading sentence two.", order = 0)
                    )
                )
        }
        val request = CrossAppAcquisitionRequest(
            targetPackageName = "com.target.app",
            expectedGeneration = 10L
        )
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(acquirer, request)
        assertTrue(result is AcquisitionResult.Success)
        val success = result as AcquisitionResult.Success
        assertEquals(2, success.document.allSegments().size)
        assertTrue(success.document.id.startsWith("crossapp_com.target.app"))
    }

    // 11. Ephemeral document persistence exclusion
    @Test
    fun `crossapp document ID starts with crossapp prefix and is marked as ephemeral source`() {
        val snapshot = CrossAppTextSnapshot(
            sourcePackageName = "com.sample.app",
            blocks = listOf(CrossAppTextBlock("Ephemeral text.", 0))
        )
        val doc = CrossAppDocumentParser.parse(snapshot)
        assertTrue(doc.id.startsWith("crossapp_"))
        assertEquals(ReadingDocumentSourceType.OTHER, doc.metadata.sourceType)
    }
}
