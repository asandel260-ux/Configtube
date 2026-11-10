package com.toolbox.multitool.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val NODE_W = 150
private const val NODE_H = 48
private const val GAP_X = 70
private const val GAP_Y = 16

private class Placed(val node: MindNode, val x: Float, val y: Float, val hasChildren: Boolean, val depth: Int)

/** Tidy left-to-right tree layout. Cost is linear in visible nodes, so huge maps stay smooth. */
private fun layout(all: List<MindNode>, rootId: Long): List<Placed> {
    val kids = all.groupBy { it.parentId }
    val byId = all.associateBy { it.id }
    val out = ArrayList<Placed>()
    var nextRow = 0f
    fun place(id: Long, depth: Int): Float {
        val n = byId[id] ?: return nextRow
        val children = if (n.collapsed) emptyList() else kids[id].orEmpty()
        val y = if (children.isEmpty()) { val r = nextRow; nextRow += NODE_H + GAP_Y; r }
        else {
            val ys = children.map { place(it.id, depth + 1) }
            (ys.first() + ys.last()) / 2f
        }
        out += Placed(n, depth * (NODE_W + GAP_X).toFloat(), y, !kids[id].isNullOrEmpty(), depth)
        return y
    }
    place(rootId, 0)
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MindMapScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).maps() }
    val scope = rememberCoroutineScope()
    val maps by dao.maps().collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<MindMap?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }

    BackHandler(enabled = open != null) { open = null }
    open?.let { MapEditor(it) { open = null }; return }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mind maps") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New map") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (maps.isEmpty()) item { EmptyState(Icons.Filled.AccountTree, "No mind maps yet", "Start from a central idea, then branch out as deep as you like.") }
            items(maps, key = { it.id }) { m ->
                Card(onClick = { open = m }, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AccountTree, null, tint = MaterialTheme.colorScheme.primary)
                        Text(m.title, Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { scope.launch { dao.deleteNodesOf(m.id); dao.deleteMap(m.id) } }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
        }
    }
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("New mind map") },
        text = { OutlinedTextField(newTitle, { newTitle = it }, label = { Text("Central topic") }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                val t = newTitle.ifBlank { "Untitled" }
                scope.launch {
                    val id = dao.insert(MindMap(title = t))
                    dao.insertNode(MindNode(mapId = id, parentId = null, text = t))
                    open = MindMap(id, t)
                }
                creating = false; newTitle = ""
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
    )
}

@Composable
private fun nodeColors(depth: Int): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when {
        depth == 0 -> cs.primary to cs.onPrimary
        depth % 3 == 1 -> cs.secondaryContainer to cs.onSecondaryContainer
        depth % 3 == 2 -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.primaryContainer to cs.onPrimaryContainer
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapEditor(map: MindMap, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).maps() }
    val scope = rememberCoroutineScope()
    val nodes by dao.nodes(map.id).collectAsState(initial = emptyList())
    val density = LocalDensity.current

    // "Focus" zooms into a node and treats its subtree as its own map; unlimited levels deep.
    var focusStack by remember { mutableStateOf(listOf<Long>()) }
    val root = nodes.firstOrNull { it.parentId == null }
    val focusId = focusStack.lastOrNull() ?: root?.id
    var selected by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<MindNode?>(null) }
    var editText by remember { mutableStateOf("") }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset(40f, 200f)) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    BackHandler { if (focusStack.isNotEmpty()) focusStack = focusStack.dropLast(1) else onClose() }

    val placed = remember(nodes, focusId) { if (focusId == null) emptyList() else layout(nodes, focusId) }
    val byId = remember(placed) { placed.associateBy { it.node.id } }
    val crumbs = focusStack.mapNotNull { id -> nodes.firstOrNull { it.id == id }?.text }

    fun subtreeIds(id: Long): List<Long> {
        val kids = nodes.groupBy { it.parentId }
        val acc = ArrayList<Long>(); val stack = ArrayDeque(listOf(id))
        while (stack.isNotEmpty()) { val c = stack.removeLast(); acc += c; kids[c]?.forEach { stack.add(it.id) } }
        return acc
    }
    fun zoomBy(f: Float) {
        val c = Offset(box.width / 2f, box.height / 2f)
        val ns = (scale * f).coerceIn(0.15f, 4f)
        pan = (pan - c) * (ns / scale) + c
        scale = ns
    }
    fun fit() {
        if (placed.isEmpty() || box == IntSize.Zero) return
        val d = density.density
        val w = (placed.maxOf { it.x } + NODE_W) * d
        val h = (placed.maxOf { it.y } + NODE_H) * d
        val s = minOf((box.width - 80f) / w, (box.height - 80f) / h, 1.5f).coerceIn(0.15f, 4f)
        scale = s; pan = Offset((box.width - w * s) / 2f, (box.height - h * s) / 2f)
    }
    LaunchedEffect(focusId, box) { if (box != IntSize.Zero) fit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf(map.title).plus(crumbs).joinToString(" › "), maxLines = 1) },
                navigationIcon = { IconButton(onClick = { if (focusStack.isNotEmpty()) focusStack = focusStack.dropLast(1) else onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { Pill("${nodes.size} ideas", Modifier.padding(end = 12.dp)) },
            )
        },
        bottomBar = {
            val sel = nodes.firstOrNull { it.id == selected }
            BottomAppBar(tonalElevation = 3.dp) {
                if (sel == null) {
                    Text("Tap an idea to edit it. Pinch to zoom, drag to move.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        IconLabelButton(Icons.Filled.SubdirectoryArrowRight, "Child", {
                            scope.launch {
                                val count = nodes.count { it.parentId == sel.id }
                                val id = dao.insertNode(MindNode(mapId = map.id, parentId = sel.id, text = "New idea", position = count))
                                dao.updateNode(sel.copy(collapsed = false)); selected = id
                            }
                        }, Modifier.weight(1f))
                        IconLabelButton(Icons.Filled.Add, "Sibling", {
                            scope.launch {
                                val count = nodes.count { it.parentId == sel.parentId }
                                selected = dao.insertNode(MindNode(mapId = map.id, parentId = sel.parentId, text = "New idea", position = count))
                            }
                        }, Modifier.weight(1f), enabled = sel.parentId != null)
                        IconLabelButton(Icons.Filled.Edit, "Edit", { editing = sel; editText = sel.text }, Modifier.weight(1f))
                        IconLabelButton(if (sel.collapsed) Icons.Filled.UnfoldMore else Icons.Filled.UnfoldLess, if (sel.collapsed) "Expand" else "Collapse",
                            { scope.launch { dao.updateNode(sel.copy(collapsed = !sel.collapsed)) } }, Modifier.weight(1f))
                        IconLabelButton(Icons.Filled.CenterFocusStrong, "Focus", { focusStack = focusStack + sel.id; selected = null }, Modifier.weight(1f))
                        IconLabelButton(Icons.Filled.Delete, "Delete", {
                            scope.launch { dao.deleteNodes(subtreeIds(sel.id)); selected = null }
                        }, Modifier.weight(1f), enabled = sel.parentId != null)
                    }
                }
            }
        },
    ) { pad ->
        Box(
            Modifier.padding(pad).fillMaxSize().onSizeChanged { box = it }.pointerInput(Unit) {
                detectTransformGestures { centroid, dragPan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(0.15f, 4f)
                    pan = (pan - centroid) * (newScale / scale) + centroid + dragPan
                    scale = newScale
                }
            },
        ) {
            val lineColor = MaterialTheme.colorScheme.outline
            Canvas(Modifier.fillMaxSize()) {
                val nw = NODE_W * density.density; val nh = NODE_H * density.density
                placed.forEach { p ->
                    val parent = p.node.parentId?.let { byId[it] } ?: return@forEach
                    val x1 = (parent.x * density.density) * scale + pan.x + nw * scale
                    val y1 = (parent.y * density.density) * scale + pan.y + nh * scale / 2
                    val x2 = (p.x * density.density) * scale + pan.x
                    val y2 = (p.y * density.density) * scale + pan.y + nh * scale / 2
                    val path = Path().apply { moveTo(x1, y1); cubicTo((x1 + x2) / 2, y1, (x1 + x2) / 2, y2, x2, y2) }
                    drawPath(path, lineColor, style = Stroke(width = 2.5f * scale))
                }
            }
            placed.forEach { p ->
                val isSel = p.node.id == selected
                val (bg, fg) = nodeColors(p.depth)
                Box(
                    Modifier
                        .offset { IntOffset((p.x * density.density * scale + pan.x).roundToInt(), (p.y * density.density * scale + pan.y).roundToInt()) }
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
                        .size(NODE_W.dp, NODE_H.dp)
                        .background(bg, RoundedCornerShape(14.dp))
                        .border(if (isSel) 3.dp else 0.dp, if (isSel) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable { selected = if (isSel) null else p.node.id },
                ) {
                    Text(
                        p.node.text + if (p.hasChildren && p.node.collapsed) "  ▸" else "",
                        Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
                        maxLines = 2, style = MaterialTheme.typography.bodySmall, color = fg,
                    )
                }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallFloatingActionButton(onClick = { zoomBy(1.3f) }) { Icon(Icons.Filled.ZoomIn, "Zoom in") }
                SmallFloatingActionButton(onClick = { zoomBy(1 / 1.3f) }) { Icon(Icons.Filled.ZoomOut, "Zoom out") }
                SmallFloatingActionButton(onClick = { fit() }) { Icon(Icons.Filled.FitScreen, "Fit to screen") }
            }
        }
    }

    editing?.let { n ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit idea") },
            text = { OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { scope.launch { dao.updateNode(n.copy(text = editText)) }; editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
