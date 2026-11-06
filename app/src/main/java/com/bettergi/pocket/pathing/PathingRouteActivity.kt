package com.bettergi.pocket.pathing

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bettergi.pocket.R
import com.bettergi.pocket.pathing.settings.PathingRouteStore
import com.bettergi.pocket.pathing.settings.RouteCatalog
import com.bettergi.pocket.service.TriggerForegroundService

/**
 * 从安装包里的路线目录里选一条。悬浮窗点「选择」后打开。
 */
class PathingRouteActivity : AppCompatActivity() {
    private val expanded = HashSet<String>()
    private var catalog = RouteCatalog(emptyList())
    private var selectedPath: String? = null
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var hint: TextView
    private lateinit var search: EditText
    private val adapter = RouteTreeAdapter { row -> onRow(row) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_picker)
        selectedPath = PathingRouteStore(this).selectedPath()
        findViewById<View>(R.id.route_picker_scrim).setOnClickListener { finish() }
        findViewById<View>(R.id.route_picker_close).setOnClickListener { finish() }
        empty = findViewById(R.id.route_picker_empty)
        hint = findViewById(R.id.route_picker_hint)
        search = findViewById(R.id.route_picker_search)
        list = findViewById(R.id.route_picker_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                render()
            }
        })
        Thread({
            val loaded = loadCatalog()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                catalog = loaded
                render()
            }
        }, "RouteCatalog").start()
    }

    private fun loadCatalog(): RouteCatalog {
        return try {
            assets.open(RouteCatalog.INDEX_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                RouteCatalog.parse(reader.readText())
            }
        } catch (_: Exception) {
            RouteCatalog(emptyList())
        }
    }

    private fun onRow(row: RouteCatalog.Row) {
        val node = row.node
        if (!node.file) {
            if (!expanded.add(node.relativePath)) expanded.remove(node.relativePath)
            render()
            return
        }
        PathingRouteStore(this).select(node.relativePath, node.name)
        ContextCompat.startForegroundService(
            this,
            Intent(this, TriggerForegroundService::class.java).apply {
                action = TriggerForegroundService.ACTION_PATHING_ROUTE
            },
        )
        finish()
    }

    private fun render() {
        if (!::list.isInitialized) return
        val listing = catalog.rows(expanded, search.text?.toString().orEmpty())
        adapter.submit(listing.rows, selectedPath)
        val message = when {
            catalog.roots.isEmpty() -> "应用里还没有路线"
            listing.rows.isEmpty() -> "没有匹配的路线"
            else -> null
        }
        empty.visibility = if (message == null) View.GONE else View.VISIBLE
        empty.text = message
        list.visibility = if (message == null) View.VISIBLE else View.GONE
        if (listing.truncated) {
            hint.visibility = View.VISIBLE
            hint.text = "只显示前 ${RouteCatalog.SEARCH_LIMIT} 条，请再缩小范围"
        } else {
            hint.visibility = View.GONE
        }
        fitList()
    }

    private var fitAttempts = 0

    private fun fitList() {
        if (list.visibility != View.VISIBLE) return
        fitAttempts = 0
        list.post { applyListHeight() }
    }

    private fun applyListHeight() {
        if (isFinishing || adapter.itemCount == 0) return
        val cap = (220 * resources.displayMetrics.density).toInt()
        val range = list.computeVerticalScrollRange()
        if (range <= 0 && fitAttempts < 2) {
            fitAttempts++
            list.post { applyListHeight() }
            return
        }
        val wanted = if (range <= 0) cap else range.coerceAtMost(cap)
        if (list.layoutParams.height != wanted) {
            list.layoutParams.height = wanted
            list.requestLayout()
        }
    }
}

private class RouteTreeAdapter(
    private val onClick: (RouteCatalog.Row) -> Unit,
) : RecyclerView.Adapter<RouteTreeAdapter.Holder>() {
    private var rows: List<RouteCatalog.Row> = emptyList()
    private var selectedPath: String? = null

    fun submit(rows: List<RouteCatalog.Row>, selectedPath: String?) {
        this.rows = rows
        this.selectedPath = selectedPath
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_route_node, parent, false)
        return Holder(view, onClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position], selectedPath)
    }

    class Holder(
        view: View,
        private val onClick: (RouteCatalog.Row) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val chevron: ImageView = view.findViewById(R.id.route_node_chevron)
        private val name: TextView = view.findViewById(R.id.route_node_name)
        private val crumb: TextView = view.findViewById(R.id.route_node_crumb)
        private val density = view.resources.displayMetrics.density
        private var row: RouteCatalog.Row? = null

        init {
            view.setOnClickListener { row?.let(onClick) }
        }

        fun bind(row: RouteCatalog.Row, selectedPath: String?) {
            this.row = row
            val node = row.node
            val vertical = (4 * density).toInt()
            itemView.setPadding(((8 + row.depth * 14) * density).toInt(), vertical, (4 * density).toInt(), vertical)
            name.text = node.name
            val selected = node.file && node.relativePath == selectedPath
            name.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (selected) R.color.overlay_gold else R.color.overlay_text,
                ),
            )
            if (row.breadcrumb.isEmpty()) {
                crumb.visibility = View.GONE
            } else {
                crumb.visibility = View.VISIBLE
                crumb.text = row.breadcrumb
            }
            if (node.file) {
                chevron.visibility = if (row.breadcrumb.isEmpty()) View.INVISIBLE else View.GONE
            } else {
                chevron.visibility = View.VISIBLE
                chevron.rotation = if (row.expanded) 90f else 0f
            }
        }
    }
}
