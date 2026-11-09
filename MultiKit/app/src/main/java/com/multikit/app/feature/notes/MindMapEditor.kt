package com.multikit.app.feature.notes

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multikit.app.ui.components.BusyOverlay
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.SuccessBanner
import com.multikit.app.ui.components.rememberTaskState
import com.multikit.app.util.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Palette & theme colors
// ---------------------------------------------------------------------------

/** The 8 selectable node stripe colors. */
internal val NodePalette = listOf(
    Color(0xFFEF5350), // red
    Color(0xFFFFA726), // orange
    Color(0xFFFFEE58), // yellow
    Color(0xFF66BB6A), // green
    Color(0xFF26A69A), // teal
    Color(0xFF42A5F5), // blue
    Color(0xFFAB47BC), // purple
    Color(0xFFEC407A), // pink
)

internal fun nodeColor(index: Int): Color =
    NodePalette[((index % NodePalette.size) + NodePalette.size) % NodePalette.size]

internal data class MapColors(
    val background: Color,
    val nodeFill: Color,
    val nodeBorder: Color,
    val selectedBorder: Color,
    val text: Color,
    val category: Color,
    val edge: Color,
    val badgeFill: Color,
    val badgeText: Color,
)

@Composable
internal fun rememberMapColors(): MapColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        MapColors(
            background = scheme.background,
            nodeFill = scheme.surface,
            nodeBorder = scheme.outlineVariant,
            selectedBorder = scheme.primary,
            text = scheme.onSurface,
            category = scheme.onSurfaceVariant,
            edge = scheme.outline,
            badgeFill = scheme.primaryContainer,
            badgeText = scheme.onPrimaryContainer,
        )
    }
}

// ---------------------------------------------------------------------------
// Editor state
// ---------------------------------------------------------------------------

/**
 * Mutable editor state. [version] is bumped on every tree/position change so
 * composition re-measures boxes; text measurement itself is cached, so dragging
 * only recomputes geometry. [dirty] drives debounced autosave.
 */
internal class MindMapState(val root: MindNode) {
    var camera by mutableStateOf(Offset.Zero)
    var zoom by mutableStateOf(1f)
    var selectedId by mutableStateOf<String?>(null)
    var version by mutableIntStateOf(0)
    var dirty by mutableStateOf(false)

    /** Pixels per dp; refreshed from composition so placement works on any density. */
    var unit: Float = 1f

    fun bump() {
        version++
        dirty = true
    }

    fun find(id: String?): MindNode? {
        if (id == null) return null
        val stack = ArrayDeque<MindNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.id == id) return node
            stack.addAll(node.children)
        }
        return null
    }

    fun parentOf(id: String): MindNode? {
        val stack = ArrayDeque<MindNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            for (child in node.children) {
                if (child.id == id) return node
                stack.addLast(child)
            }
        }
        return null
    }

    /** Add a child placed to the right of the parent, fanned vertically. */
    fun addChild(parent: MindNode): MindNode {
        val index = parent.children.size
        val child = MindNode(text = "New idea", colorIndex = parent.colorIndex)
        child.x = parent.x + 260f * unit
        child.y = parent.y + index * 90f * unit - (index * 90f * unit) / 2f
        parent.children.add(child)
        parent.collapsed = false
        selectedId = child.id
        bump()
        return child
    }

    /** Delete a node and its subtree. The root can never be deleted. */
    fun delete(node: MindNode): Boolean {
        if (node.id == root.id) return false
        val parent = parentOf(node.id) ?: return false
        parent.children.remove(node)
        selectedId = parent.id
        bump()
        return true
    }

    fun toggleCollapse(node: MindNode) {
        node.collapsed = !node.collapsed
        bump()
    }

    /**
     * Tidy tree: recursive-style vertical layout done in two iterative passes
     * (leaves get sequential slots left-to-right, parents center over children).
     * Only the visible tree is laid out; collapsed subtrees keep their positions.
     */
    fun autoLayout() {
        val hGap = 260f * unit
        val vGap = 90f * unit
        val preorder = ArrayList<Pair<MindNode, Int>>()
        val stack = ArrayDeque<Pair<MindNode, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            preorder.add(node to depth)
            if (!node.collapsed) {
                for (i in node.children.indices.reversed()) {
                    stack.addLast(node.children[i] to depth + 1)
                }
            }
        }
        var cursor = 0f
        for ((node, depth) in preorder) {
            node.x = depth * hGap
            val kids = if (node.collapsed) emptyList() else node.children
            if (kids.isEmpty()) {
                node.y = cursor
                cursor += vGap
            }
        }
        for ((node, _) in preorder.asReversed()) {
            val kids = if (node.collapsed) emptyList() else node.children
            if (kids.isNotEmpty()) {
                node.y = (kids.first().y + kids.last().y) / 2f
            }
        }
        bump()
    }
}

// ---------------------------------------------------------------------------
// Measurement (shared by the live canvas and PNG export)
// ---------------------------------------------------------------------------

internal data class NodeTextLayouts(val main: TextLayoutResult, val category: TextLayoutResult?)

internal data class NodeBox(
    val node: MindNode,
    val rect: Rect,
    val text: TextLayoutResult,
    val categoryText: TextLayoutResult?,
    val padX: Float,
    val padY: Float,
    val stripeW: Float,
    val gap: Float,
)

internal data class MapLayout(
    val boxes: List<NodeBox>,
    val edges: List<Pair<NodeBox, NodeBox>>,
)

/**
 * Measure all visible nodes (collapsed subtrees hidden) and collect parent→child
 * edges. Text is measured with Compose's [TextMeasurer] so Persian/RTL bidi and
 * shaping are correct; results are cached by text+category+density.
 *
 * [posScale] multiplies world positions (used by export); [density] may be a
 * scaled density so exported text is rendered crisply at the export resolution.
 */
internal fun measureMindMap(
    root: MindNode,
    measurer: TextMeasurer,
    density: Density,
    cache: MutableMap<String, NodeTextLayouts>,
    posScale: Float,
): MapLayout {
    val maxTextPx = with(density) { 220.dp.toPx() }
    val padX = with(density) { 12.dp.toPx() }
    val padY = with(density) { 10.dp.toPx() }
    val stripeW = with(density) { 6.dp.toPx() }
    val gap = with(density) { 3.dp.toPx() }
    val minW = with(density) { 48.dp.toPx() }
    val mainStyle = TextStyle(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Medium,
        textDirection = TextDirection.Content,
    )
    val categoryStyle = TextStyle(
        fontSize = 10.sp,
        lineHeight = 13.sp,
        textDirection = TextDirection.Content,
    )

    val boxes = ArrayList<NodeBox>()
    val edges = ArrayList<Pair<NodeBox, NodeBox>>()
    val stack = ArrayDeque<Pair<MindNode, NodeBox?>>()
    stack.addLast(root to null)
    while (stack.isNotEmpty()) {
        val (node, parentBox) = stack.removeLast()
        val key = buildString {
            append(node.text)
            append('\u0000')
            append(node.category)
            append('\u0000')
            append(density.density)
            append('\u0000')
            append(density.fontScale)
        }
        var layouts = cache[key]
        if (layouts == null) {
            if (cache.size > 1024) cache.clear()
            val main = measurer.measure(
                text = node.text.ifBlank { " " },
                style = mainStyle,
                constraints = Constraints(maxWidth = maxTextPx),
                softWrap = true,
                density = density,
            )
            val category = if (node.category.isBlank()) null else measurer.measure(
                text = node.category,
                style = categoryStyle,
                constraints = Constraints(maxWidth = maxTextPx),
                softWrap = true,
                density = density,
            )
            layouts = NodeTextLayouts(main, category)
            cache[key] = layouts
        }
        val textW = layouts.main.size.width.toFloat()
        val textH = layouts.main.size.height.toFloat()
        val catH = layouts.category?.size?.height?.toFloat() ?: 0f
        val w = (textW + padX * 2 + stripeW).coerceAtLeast(minW)
        val h = padY * 2 + textH + (if (layouts.category != null) catH + gap else 0f)
        val left = node.x * posScale
        val top = node.y * posScale
        val box = NodeBox(
            node = node,
            rect = Rect(left, top, left + w, top + h),
            text = layouts.main,
            categoryText = layouts.category,
            padX = padX,
            padY = padY,
            stripeW = stripeW,
            gap = gap,
        )
        boxes.add(box)
        if (parentBox != null) edges.add(parentBox to box)
        if (!node.collapsed) {
            for (i in node.children.indices.reversed()) {
                stack.addLast(node.children[i] to box)
            }
        }
    }
    return MapLayout(boxes, edges)
}

private fun countDescendants(node: MindNode): Int {
    var count = 0
    val stack = ArrayDeque<MindNode>()
    stack.addAll(node.children)
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        count++
        stack.addAll(n.children)
    }
    return count
}

// ---------------------------------------------------------------------------
// Shared native renderer (live canvas + PNG export use the exact same code)
// ---------------------------------------------------------------------------

/**
 * Draw edges (cubic Bézzier, parent right edge → child left edge) first, then
 * nodes: rounded rect, clipped category color stripe, border, wrapped text via
 * [TextPainter] (correct bidi/shaping for Persian), and a "+N" badge on collapsed
 * nodes with hidden descendants. All coordinates are already in draw-space.
 */
internal fun drawMindMapNative(
    canvas: android.graphics.Canvas,
    boxes: List<NodeBox>,
    edges: List<Pair<NodeBox, NodeBox>>,
    selectedId: String?,
    colors: MapColors,
    edgeWidthPx: Float,
    cornerPx: Float,
    borderPx: Float,
    badgeRadiusPx: Float,
    badgeTextPx: Float,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val path = Path()

    // Edges first so nodes paint over them.
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = edgeWidthPx
    paint.strokeCap = Paint.Cap.ROUND
    paint.color = colors.edge.toArgb()
    for ((parent, child) in edges) {
        val sx = parent.rect.right
        val sy = parent.rect.center.y
        val ex = child.rect.left
        val ey = child.rect.center.y
        val dx = ((ex - sx) * 0.5f).coerceAtLeast(cornerPx * 2f)
        path.reset()
        path.moveTo(sx, sy)
        path.cubicTo(sx + dx, sy, ex - dx, ey, ex, ey)
        canvas.drawPath(path, paint)
    }

    val composeCanvas = ComposeCanvas(canvas)
    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = badgeTextPx
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    for (box in boxes) {
        val r = box.rect
        val selected = box.node.id == selectedId

        // Fill.
        paint.style = Paint.Style.FILL
        paint.color = colors.nodeFill.toArgb()
        canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, cornerPx, cornerPx, paint)

        // Category color stripe, clipped to the rounded rect.
        canvas.save()
        path.reset()
        path.addRoundRect(r.left, r.top, r.right, r.bottom, cornerPx, cornerPx, Path.Direction.CW)
        canvas.clipPath(path)
        paint.color = nodeColor(box.node.colorIndex).toArgb()
        canvas.drawRect(r.left, r.top, r.left + box.stripeW, r.bottom, paint)
        canvas.restore()

        // Border (highlighted when selected).
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (selected) borderPx * 2f else borderPx
        paint.color = (if (selected) colors.selectedBorder else colors.nodeBorder).toArgb()
        canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, cornerPx, cornerPx, paint)

        // Text: optional small category line, then the wrapped node text.
        val tx = r.left + box.stripeW + box.padX
        var ty = r.top + box.padY
        val categoryLayout = box.categoryText
        if (categoryLayout != null) {
            canvas.save()
            canvas.translate(tx, ty)
            TextPainter.paint(composeCanvas, categoryLayout, color = colors.category)
            canvas.restore()
            ty += categoryLayout.size.height + box.gap
        }
        canvas.save()
        canvas.translate(tx, ty)
        TextPainter.paint(composeCanvas, box.text, color = colors.text)
        canvas.restore()

        // Collapse indicator: +N hidden descendants.
        if (box.node.collapsed && box.node.children.isNotEmpty()) {
            val hidden = countDescendants(box.node)
            val bx = r.right
            val by = r.center.y
            paint.style = Paint.Style.FILL
            paint.color = colors.badgeFill.toArgb()
            canvas.drawCircle(bx, by, badgeRadiusPx, paint)
            badgePaint.color = colors.badgeText.toArgb()
            canvas.drawText("+$hidden", bx, by - (badgePaint.ascent() + badgePaint.descent()) / 2f, badgePaint)
        }
    }
}

// ---------------------------------------------------------------------------
// Editor screen
// ---------------------------------------------------------------------------

/**
 * Full-screen infinite mind-map canvas with its own Scaffold (no scrolling
 * content so the canvas gets the whole area). Gestures are handled by a single
 * [awaitEachGesture] loop: tap = select, double-tap = edit dialog, one-finger
 * drag on a node = move it, one-finger drag on empty space = pan, two fingers =
 * pinch zoom + pan. A unified loop avoids the event-consumption conflicts of
 * running tap/drag/transform detectors in parallel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTextApi::class)
@Composable
internal fun MindMapEditorScreen(
    item: NoteItem,
    store: NoteStore,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember {
        MindMapState(item.root ?: MindNode(text = item.title.ifBlank { "Central idea" }))
    }
    state.unit = with(density) { 1.dp.toPx() }

    val measurer = rememberTextMeasurer()
    val measureCache = remember { HashMap<String, NodeTextLayouts>() }
    val colors = rememberMapColors()
    val mapLayout = remember(state.version, state.root, density, measurer) {
        measureMindMap(state.root, measurer, density, measureCache, 1f)
    }
    val boxes = mapLayout.boxes

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var cameraInitialized by remember { mutableStateOf(false) }
    var mapTitle by remember { mutableStateOf(item.title) }
    var editTarget by remember { mutableStateOf<MindNode?>(null) }
    var deleteTarget by remember { mutableStateOf<MindNode?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(item.title) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val task = rememberTaskState()
    val selectedNode = state.find(state.selectedId)

    fun zoomBy(factor: Float, pivot: Offset) {
        val old = state.zoom
        val new = (old * factor).coerceIn(0.2f, 3f)
        if (new == old) return
        state.camera = pivot - (pivot - state.camera) * (new / old)
        state.zoom = new
    }

    fun centerOnRoot() {
        if (canvasSize == IntSize.Zero) return
        val rootBox = boxes.firstOrNull { it.node.id == state.root.id } ?: boxes.firstOrNull() ?: return
        state.zoom = 1f
        state.camera = Offset(canvasSize.width / 2f, canvasSize.height / 2f) - rootBox.rect.center
    }

    suspend fun saveNow(showMessage: Boolean) {
        item.title = mapTitle
        item.root = state.root
        item.updatedAt = System.currentTimeMillis()
        store.save(item)
        state.dirty = false
        if (showMessage) savedMessage = "Saved"
    }

    fun saveAndExit() {
        scope.launch {
            try {
                saveNow(false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Best effort on exit; autosave errors are surfaced in-screen.
            }
            onDone()
        }
    }

    fun exportPng() {
        val snapshot = boxes
        if (snapshot.isEmpty()) {
            errorMessage = "Nothing to export"
            return
        }
        task.launch(scope) {
            val marginPx = with(density) { 40.dp.toPx() }
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (b in snapshot) {
                val r = b.rect
                if (r.left < minX) minX = r.left
                if (r.top < minY) minY = r.top
                if (r.right > maxX) maxX = r.right
                if (r.bottom > maxY) maxY = r.bottom
            }
            val worldW = (maxX - minX) + marginPx * 2f
            val worldH = (maxY - minY) + marginPx * 2f
            // Render at up to 2x for crispness, capped so the bitmap stays ≤ 4096px.
            val scale = 2f.coerceAtMost(4096f / maxOf(worldW, worldH))
            val exportDensity = Density(density.density * scale, density.fontScale)
            val exportLayout = measureMindMap(state.root, measurer, exportDensity, measureCache, scale)
            val width = ceil(worldW * scale).toInt().coerceIn(1, 4096)
            val height = ceil(worldH * scale).toInt().coerceIn(1, 4096)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(colors.background.toArgb())
                canvas.translate((marginPx - minX) * scale, (marginPx - minY) * scale)
                drawMindMapNative(
                    canvas = canvas,
                    boxes = exportLayout.boxes,
                    edges = exportLayout.edges,
                    selectedId = null,
                    colors = colors,
                    edgeWidthPx = with(exportDensity) { 2.dp.toPx() },
                    cornerPx = with(exportDensity) { 12.dp.toPx() },
                    borderPx = with(exportDensity) { 1.5.dp.toPx() },
                    badgeRadiusPx = with(exportDensity) { 11.dp.toPx() },
                    badgeTextPx = with(exportDensity) { 9.sp.toPx() },
                )
                val file = Files.tempFile(context, "mindmap_", "png")
                withContext(Dispatchers.IO) {
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Files.exportToDownloads(context, file, "image/png")
                }
                Files.share(context, file)
                task.message = "PNG saved to Downloads"
            } finally {
                bitmap.recycle()
            }
        }
    }

    BackHandler { saveAndExit() }

    // Debounced autosave: any bump restarts the 800ms timer.
    LaunchedEffect(state.version) {
        if (state.dirty) {
            delay(800)
            try {
                saveNow(false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                errorMessage = "Autosave failed: ${t.message}"
            }
        }
    }
    LaunchedEffect(canvasSize, boxes.size) {
        if (!cameraInitialized && canvasSize != IntSize.Zero && boxes.isNotEmpty()) {
            centerOnRoot()
            cameraInitialized = true
        }
    }
    LaunchedEffect(savedMessage) {
        if (savedMessage != null) {
            delay(2000)
            savedMessage = null
        }
    }

    // Latest boxes for the gesture loop without restarting pointerInput mid-drag.
    val boxesRef by rememberUpdatedState(boxes)
    val canvasCenter = Offset(canvasSize.width / 2f, canvasSize.height / 2f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        mapTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            renameText = mapTitle
                            showRename = true
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { saveAndExit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                saveNow(true)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                errorMessage = "Save failed: ${t.message}"
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                    IconButton(onClick = { exportPng() }) {
                        Icon(Icons.Filled.Share, contentDescription = "Export PNG")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            MapToolbar(
                selectedExists = selectedNode != null,
                canDelete = selectedNode != null && selectedNode.id != state.root.id,
                collapseLabel = if (selectedNode?.collapsed == true) "Expand node" else "Collapse node",
                onAdd = { state.addChild(selectedNode ?: state.root) },
                onEdit = { selectedNode?.let { editTarget = it } },
                onDelete = { selectedNode?.let { if (it.id != state.root.id) deleteTarget = it } },
                onCollapse = { selectedNode?.let { state.toggleCollapse(it) } },
                onAutoLayout = { state.autoLayout() },
                onZoomIn = { zoomBy(1.25f, canvasCenter) },
                onZoomOut = { zoomBy(0.8f, canvasCenter) },
                onResetView = { centerOnRoot() },
                onExport = { exportPng() },
            )
        },
        containerColor = colors.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(state) {
                        val slop = viewConfiguration.touchSlop
                        val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis.toLong()
                        var lastTapUptime = 0L
                        var lastTapPos = Offset.Zero
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val world = (down.position - state.camera) / state.zoom
                            val all = boxesRef
                            var hit: NodeBox? = null
                            for (i in all.indices.reversed()) {
                                if (all[i].rect.contains(world)) {
                                    hit = all[i]
                                    break
                                }
                            }
                            val dragNode = hit?.node
                            if (dragNode != null) state.selectedId = dragNode.id

                            var lastCentroid = down.position
                            var lastSpan = 0f
                            var moved = 0f
                            var multiTouch = false
                            var upTime = down.uptimeMillis
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    upTime = event.changes.firstOrNull()?.uptimeMillis ?: upTime
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                var sum = Offset.Zero
                                for (c in pressed) sum += c.position
                                val centroid = sum / pressed.size.toFloat()
                                if (pressed.size >= 2) {
                                    val span = (pressed[0].position - pressed[1].position).getDistance()
                                    if (multiTouch) {
                                        if (lastSpan > 0f && span > 0f) zoomBy(span / lastSpan, centroid)
                                        state.camera += centroid - lastCentroid
                                    }
                                    multiTouch = true
                                    lastSpan = span
                                } else if (!multiTouch) {
                                    val delta = centroid - lastCentroid
                                    moved += delta.getDistance()
                                    if (dragNode != null) {
                                        if (delta.x != 0f || delta.y != 0f) {
                                            dragNode.x += delta.x / state.zoom
                                            dragNode.y += delta.y / state.zoom
                                            state.bump()
                                        }
                                    } else {
                                        state.camera += delta
                                    }
                                    lastSpan = 0f
                                }
                                lastCentroid = centroid
                                event.changes.forEach { it.consume() }
                            }
                            if (!multiTouch && moved <= slop) {
                                val nearLastTap =
                                    (down.position - lastTapPos).getDistance() <= slop * 2f
                                if (lastTapUptime != 0L && upTime - lastTapUptime <= doubleTapMs && nearLastTap) {
                                    // Double tap → edit dialog.
                                    lastTapUptime = 0L
                                    if (dragNode != null) editTarget = dragNode
                                } else {
                                    // Single tap → select (or deselect on empty canvas).
                                    lastTapUptime = upTime
                                    lastTapPos = down.position
                                    state.selectedId = dragNode?.id
                                }
                            }
                        }
                    },
            ) {
                // Subtle world-aligned dot grid for the infinite-canvas feel.
                val gridStep = 48.dp.toPx() * state.zoom
                if (gridStep >= 24f) {
                    val gridColor = colors.nodeBorder.copy(alpha = 0.45f)
                    val ox = ((state.camera.x % gridStep) + gridStep) % gridStep
                    val oy = ((state.camera.y % gridStep) + gridStep) % gridStep
                    val dotR = 1.dp.toPx()
                    var gx = ox
                    while (gx < size.width) {
                        var gy = oy
                        while (gy < size.height) {
                            drawCircle(gridColor, dotR, Offset(gx, gy))
                            gy += gridStep
                        }
                        gx += gridStep
                    }
                }
                val native = drawContext.canvas.nativeCanvas
                native.save()
                native.translate(state.camera.x, state.camera.y)
                native.scale(state.zoom, state.zoom)
                drawMindMapNative(
                    canvas = native,
                    boxes = boxes,
                    edges = mapLayout.edges,
                    selectedId = state.selectedId,
                    colors = colors,
                    edgeWidthPx = 2.dp.toPx() / state.zoom,
                    cornerPx = 12.dp.toPx(),
                    borderPx = 1.5.dp.toPx(),
                    badgeRadiusPx = 11.dp.toPx(),
                    badgeTextPx = 9.sp.toPx(),
                )
                native.restore()
            }

            Text(
                "${(state.zoom * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = colors.text,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(colors.nodeFill.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ErrorBanner(errorMessage)
                SuccessBanner(savedMessage)
                SuccessBanner(task.message)
                ErrorBanner(task.error)
            }

            BusyOverlay(task.busy, "Exporting PNG…")
        }
    }

    // Edit node dialog: text + category + 8-color picker.
    editTarget?.let { node ->
        NodeEditDialog(
            node = node,
            onDismiss = { editTarget = null },
            onApply = { text, category, colorIndex ->
                node.text = text
                node.category = category
                node.colorIndex = colorIndex
                state.bump()
                editTarget = null
            },
        )
    }

    deleteTarget?.let { node ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete node?") },
            text = { Text("\"${node.text}\" and its ${countDescendants(node)} child node(s) will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    state.delete(node)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename mind map") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    mapTitle = renameText.ifBlank { "Untitled mind map" }
                    state.bump()
                    showRename = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Toolbar & dialogs
// ---------------------------------------------------------------------------

@Composable
private fun MapToolbar(
    selectedExists: Boolean,
    canDelete: Boolean,
    collapseLabel: String,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCollapse: () -> Unit,
    onAutoLayout: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetView: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapToolButton(Icons.Filled.Add, "Add child", onAdd)
            MapToolButton(Icons.Filled.Edit, "Edit node", onEdit, selectedExists)
            MapToolButton(Icons.Filled.Delete, "Delete node", onDelete, canDelete)
            MapToolButton(Icons.Filled.AccountTree, collapseLabel, onCollapse, selectedExists)
            MapToolButton(Icons.Filled.AutoAwesome, "Auto-layout", onAutoLayout)
            MapToolButton(Icons.Filled.ZoomIn, "Zoom in", onZoomIn)
            MapToolButton(Icons.Filled.ZoomOut, "Zoom out", onZoomOut)
            MapToolButton(Icons.Filled.CenterFocusStrong, "Reset view", onResetView)
            MapToolButton(Icons.Filled.Share, "Export PNG", onExport)
        }
    }
}

@Composable
private fun MapToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun NodeEditDialog(
    node: MindNode,
    onDismiss: () -> Unit,
    onApply: (String, String, Int) -> Unit,
) {
    var text by remember(node.id) { mutableStateOf(node.text) }
    var category by remember(node.id) { mutableStateOf(node.category) }
    var colorIndex by remember(node.id) { mutableIntStateOf(node.colorIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit node") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    NodePalette.forEachIndexed { index, color ->
                        val isSelected = index == colorIndex
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(color, CircleShape)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable { colorIndex = index },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(text, category, colorIndex) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}