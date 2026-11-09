package com.multikit.app.feature.notes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Item types persisted by [NoteStore]. */
object NoteTypes {
    const val NOTE = "note"
    const val MINDMAP = "mindmap"
}

/**
 * A single mind-map node. Trees are arbitrarily deep; [children] is mutable so the
 * editor can add/remove/drag nodes in place. [x]/[y] are world coordinates in px
 * (canvas space before camera pan/zoom is applied).
 */
data class MindNode(
    val id: String = UUID.randomUUID().toString(),
    var text: String = "",
    var colorIndex: Int = 0,
    var category: String = "",
    val children: MutableList<MindNode> = mutableListOf(),
    var collapsed: Boolean = false,
    var x: Float = 0f,
    var y: Float = 0f,
)

/** A saved list entry: either a plain note ([body]) or a mind map ([root]). */
data class NoteItem(
    val id: String = UUID.randomUUID().toString(),
    val type: String = NoteTypes.NOTE,
    var title: String,
    var updatedAt: Long = System.currentTimeMillis(),
    var body: String = "",
    var root: MindNode? = null,
)

/** Total number of nodes in a tree (root included), iterative so depth is unlimited. */
fun countNodes(root: MindNode?): Int {
    if (root == null) return 0
    var count = 0
    val stack = ArrayDeque<MindNode>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        count++
        stack.addAll(node.children)
    }
    return count
}

/**
 * JSON-file persistence for notes and mind maps in `ctx.filesDir/notes/`.
 *
 * Writes are atomic-ish: serialize to `<id>.json.tmp`, fsync, then rename over
 * `<id>.json`. Disk work runs on [Dispatchers.IO]; JSON serialization for [save]
 * happens on the caller thread so the tree is snapshotted consistently.
 * Blocking *Sync variants are provided for callers already on IO.
 */
class NoteStore(context: Context) {

    private val dir: File = File(context.applicationContext.filesDir, "notes").apply { mkdirs() }

    /** Load every valid item, newest first. Corrupt files are skipped. */
    suspend fun loadAll(): List<NoteItem> = withContext(Dispatchers.IO) { loadAllSync() }

    fun loadAllSync(): List<NoteItem> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val items = ArrayList<NoteItem>(files.size)
        for (file in files) {
            try {
                items.add(fromJson(JSONObject(file.readText(Charsets.UTF_8))))
            } catch (_: Throwable) {
                // Skip unreadable/corrupt entries instead of failing the whole list.
            }
        }
        items.sortByDescending { it.updatedAt }
        return items
    }

    /** Serialize on the caller thread, write on IO. */
    suspend fun save(item: NoteItem) {
        val json = toJson(item).toString()
        withContext(Dispatchers.IO) { writeAtomic(item.id, json) }
    }

    fun saveSync(item: NoteItem) {
        writeAtomic(item.id, toJson(item).toString())
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { deleteSync(id) }
    }

    fun deleteSync(id: String): Boolean {
        val name = fileName(id)
        File(dir, "$name.tmp").delete()
        return File(dir, name).delete()
    }

    // ------------------------------------------------------------------ files

    private fun fileName(id: String): String {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return (if (safe.isBlank()) UUID.randomUUID().toString() else safe) + ".json"
    }

    private fun writeAtomic(id: String, json: String) {
        val name = fileName(id)
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // Some filesystems refuse rename-over-existing; fall back to delete+rename.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Could not save note $id")
            }
        }
    }

    // ------------------------------------------------------------------- JSON

    private fun toJson(item: NoteItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("type", item.type)
        put("title", item.title)
        put("updatedAt", item.updatedAt)
        put("body", item.body)
        item.root?.let { put("root", nodeToJson(it)) }
    }

    private fun nodeToJson(node: MindNode): JSONObject = JSONObject().apply {
        put("id", node.id)
        put("text", node.text)
        put("colorIndex", node.colorIndex)
        put("category", node.category)
        put("collapsed", node.collapsed)
        put("x", node.x.toDouble())
        put("y", node.y.toDouble())
        val arr = JSONArray()
        for (child in node.children) arr.put(nodeToJson(child))
        put("children", arr)
    }

    private fun fromJson(obj: JSONObject): NoteItem {
        val type = if (obj.optString("type") == NoteTypes.MINDMAP) NoteTypes.MINDMAP else NoteTypes.NOTE
        val id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() }
        val title = obj.optString("title", "Untitled")
        val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        val body = obj.optString("body", "")
        var root = obj.optJSONObject("root")?.let { nodeFromJson(it, 0) }
        if (type == NoteTypes.MINDMAP && root == null) {
            root = MindNode(text = title.ifBlank { "Central idea" })
        }
        return NoteItem(id = id, type = type, title = title, updatedAt = updatedAt, body = body, root = root)
    }

    private fun nodeFromJson(obj: JSONObject, depth: Int): MindNode {
        val node = MindNode(
            id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() },
            text = obj.optString("text", ""),
            colorIndex = obj.optInt("colorIndex", 0),
            category = obj.optString("category", ""),
            collapsed = obj.optBoolean("collapsed", false),
            x = obj.optDouble("x", 0.0).let { if (it.isFinite()) it.toFloat() else 0f },
            y = obj.optDouble("y", 0.0).let { if (it.isFinite()) it.toFloat() else 0f },
        )
        // Depth guard only protects against corrupt/hostile files; the editor itself
        // supports unlimited nesting.
        if (depth < 256) {
            val arr = obj.optJSONArray("children")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { node.children.add(nodeFromJson(it, depth + 1)) }
                }
            }
        }
        return node
    }
}