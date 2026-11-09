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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val NODE_W = 150
private const val NODE_H = 48
private const val GAP_X = 70
private const val GAP_Y = 16

private class Placed(val node: MindNode, val x: Float, val y: Float, val hasChildren: Boolean)

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
        out += Placed(n, depth * (NODE_W + GAP_X).toFloat(), y, !kids[id].isNullOrEmpty())
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
        topBar = { TopAppBar(title = { Text("Mind maps") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New map") } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(maps, key = { it.id }) { m ->
                ListItem(
                    headlineContent = { Text(m.title) },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { dao.deleteNodesOf(m.id); dao.deleteMap(m.id) } }) { Icon(Icons.Filled.Delete, "Delete") }
                    },
                    modifier = Modifier.clickable { open = m },
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapEditor(map: MindMap, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).maps() }
    val scope = rememberCoroutineScope()
    val nodes by dao.nodes(map.id).collectAsState(initial = emptyList())
    val density = LocalDensity.current

    // "Focus" lets you zoom into any node and treat its subtree as its own map; unlimited levels deep.
    var focusStack by remember { mutableStateOf(listOf<Long>()) }
    val root = nodes.firstOrNull { it.parentId == null }
    val focusId = focusStack.lastOrNull() ?: root?.id
    var selected by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<MindNode?>(null) }
    var editText by remember { mutableStateOf("") }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset(40f, 200f)) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf(map.title).plus(crumbs).joinToString(" › "), maxLines = 1) },
                navigationIcon = { TextButton(onClick = { if (focusStack.isNotEmpty()) focusStack = focusStack.dropLast(1) else onClose() }) { Text("Back") } },
            )
        },
        bottomBar = {
            val sel = nodes.firstOrNull { it.id == selected }
            if (sel != null) BottomAppBar {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        scope.launch {
                            val count = nodes.count { it.parentId == sel.id }
                            val id = dao.insertNode(MindNode(mapId = map.id, parentId = sel.id, text = "New idea", position = count))
                            dao.updateNode(sel.copy(collapsed = false)); selected = id
                        }
                    }) { Text("+ Child") }
                    TextButton(enabled = sel.parentId != null, onClick = {
                        scope.launch {
                            val count = nodes.count { it.parentId == sel.parentId }
                            selected = dao.insertNode(MindNode(mapId = map.id, parentId = sel.parentId, text = "New idea", position = count))
                        }
                    }) { Text("+ Sibling") }
                    TextButton(onClick = { editing = sel; editText = sel.text }) { Text("Edit") }
                    TextButton(onClick = { scope.launch { dao.updateNode(sel.copy(collapsed = !sel.collapsed)) } }) { Text(if (sel.collapsed) "Expand" else "Collapse") }
                    TextButton(onClick = { focusStack = focusStack + sel.id; scale = 1f; pan = Offset(40f, 200f) }) { Text("Focus") }
                    TextButton(enabled = sel.parentId != null, onClick = {
                        scope.launch { dao.deleteNodes(subtreeIds(sel.id)); selected = null }
                    }) { Text("Delete") }
                }
            }
        },
    ) { pad ->
        Box(
            Modifier.padding(pad).fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { centroid, dragPan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(0.15f, 4f)
                    // zoom around the fingers, then pan
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
                    drawPath(path, lineColor, style = Stroke(width = 2f * scale))
                }
            }
            placed.forEach { p ->
                val isSel = p.node.id == selected
                Box(
                    Modifier
                        .offset { IntOffset((p.x * density.density * scale + pan.x).roundToInt(), (p.y * density.density * scale + pan.y).roundToInt()) }
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
                        .size(NODE_W.dp, NODE_H.dp)
                        .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .border(if (isSel) 2.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .clickable { selected = p.node.id },
                ) {
                    Text(
                        p.node.text + if (p.hasChildren && p.node.collapsed) "  ▸" else "",
                        Modifier.align(androidx.compose.ui.Alignment.Center).padding(horizontal = 8.dp),
                        maxLines = 2, style = MaterialTheme.typography.bodySmall,
                    )
                }
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
