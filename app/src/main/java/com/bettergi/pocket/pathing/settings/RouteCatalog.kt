package com.bettergi.pocket.pathing.settings

import java.text.Collator
import java.util.Locale

/**
 * 安装包内的路线目录。索引每行一条相对 `assets/pathing/routes/` 的路径，
 * 目录层级就是选择界面里的树。
 */
class RouteCatalog(val roots: List<Node>) {
    data class Node(
        val name: String,
        val relativePath: String,
        val file: Boolean,
        val children: List<Node>,
    )

    data class Row(
        val node: Node,
        val depth: Int,
        val breadcrumb: String,
        val expanded: Boolean,
    )

    data class Listing(val rows: List<Row>, val truncated: Boolean)

    /**
     * 没有搜索词时按展开状态输出树。有搜索词时只列出命中的文件，并带上所在目录。
     */
    fun rows(expanded: Set<String>, query: String, limit: Int = SEARCH_LIMIT): Listing {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            val out = ArrayList<Row>()
            fun walk(nodes: List<Node>, depth: Int) {
                for (node in nodes) {
                    val open = !node.file && node.relativePath in expanded
                    out += Row(node, depth, "", open)
                    if (open) walk(node.children, depth + 1)
                }
            }
            walk(roots, 0)
            return Listing(out, false)
        }
        val out = ArrayList<Row>()
        fun walk(nodes: List<Node>) {
            for (node in nodes) {
                if (out.size > limit) return
                if (node.file) {
                    if (node.name.contains(keyword, ignoreCase = true) ||
                        node.relativePath.contains(keyword, ignoreCase = true)
                    ) {
                        val crumb = node.relativePath.substringBeforeLast('/', "")
                        out += Row(node, 0, crumb, false)
                    }
                } else {
                    walk(node.children)
                }
            }
        }
        walk(roots)
        return Listing(out.take(limit), out.size > limit)
    }

    companion object {
        const val INDEX_ASSET = "pathing/routes-index.txt"
        const val ROOT_ASSET = "pathing/routes"
        const val SEARCH_LIMIT = 80

        private val nameOrder: Collator = Collator.getInstance(Locale.CHINA)

        fun parse(text: String): RouteCatalog {
            val root = Dir("", "")
            val seen = HashSet<String>()
            for (raw in text.lineSequence()) {
                val path = raw.trim().removePrefix("\uFEFF").replace('\\', '/')
                if (path.isEmpty() || path.startsWith("#")) continue
                if (!path.endsWith(".json", ignoreCase = true)) continue
                if (!seen.add(path)) continue
                val parts = path.split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue
                var dir = root
                val acc = ArrayList<String>(parts.size - 1)
                for (i in 0 until parts.lastIndex) {
                    acc += parts[i]
                    val rel = acc.joinToString("/")
                    dir = dir.dirs.getOrPut(parts[i]) { Dir(parts[i], rel) }
                }
                val fileName = parts.last()
                dir.files += Node(stem(fileName), path, true, emptyList())
            }
            return RouteCatalog(root.toChildren())
        }

        private fun stem(fileName: String): String =
            if (fileName.endsWith(".json", ignoreCase = true)) fileName.dropLast(5) else fileName

        private class Dir(val name: String, val relativePath: String) {
            val dirs = LinkedHashMap<String, Dir>()
            val files = ArrayList<Node>()

            fun toChildren(): List<Node> {
                val folders = dirs.values.map { Node(it.name, it.relativePath, false, it.toChildren()) }
                return (folders + files).sortedWith { a, b ->
                    val group = a.file.compareTo(b.file)
                    if (group != 0) group else nameOrder.compare(a.name, b.name)
                }
            }
        }
    }
}
