package com.mundymorning.touchweathertracker

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Widget previews are inflated as RemoteViews; any other view type shows "Problem loading widget" in the launcher picker. */
class WidgetPreviewLayoutTest {
    private val allowed = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout", "AbsoluteLayout",
        "TextView", "ImageView", "Button", "ImageButton", "ProgressBar", "Chronometer", "TextClock", "AnalogClock",
        "ViewFlipper", "ListView", "GridView", "StackView", "AdapterViewFlipper",
    )

    @Test fun previewAndLoadingLayoutsUseOnlyRemoteViewsSafeElements() {
        val dir = listOf(File("src/main/res/layout"), File("app/src/main/res/layout")).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.startsWith("preview_") || f.name == "widget_loading.xml" }!!.toList()
        assertTrue("expected preview layouts", files.size >= 10)
        for (f in files) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val all = doc.getElementsByTagName("*")
            for (i in 0 until all.length) assertTrue("${f.name}: <${all.item(i).nodeName}> is not allowed in RemoteViews", all.item(i).nodeName in allowed)
        }
    }
}
