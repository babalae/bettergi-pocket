package com.bettergi.pocket.pathing.tp

/**
 * 最小费用分配。行数可以少于列数，多出来的列用零代价虚行补成方阵。
 * 传送图标对齐用它做匈牙利分配，来源是 BGI 调用的 `HungarianAlgorithm.FindAssignments`。
 */
object Hungarian {
    fun assign(costs: Array<IntArray>): IntArray {
        if (costs.isEmpty()) return IntArray(0)
        val rows = costs.size
        val cols = costs[0].size
        val n = maxOf(rows, cols)
        val square = Array(n) { LongArray(n) }
        for (row in 0 until n) {
            for (col in 0 until n) {
                square[row][col] = if (row < rows && col < cols) costs[row][col].toLong() else 0L
            }
        }
        val columnOfRow = assignSquare(square)
        return IntArray(rows) { row ->
            val column = columnOfRow[row]
            if (column in 0 until cols) column else -1
        }
    }

    private fun assignSquare(cost: Array<LongArray>): IntArray {
        val n = cost.size
        val u = LongArray(n + 1)
        val v = LongArray(n + 1)
        val parent = IntArray(n + 1)
        val way = IntArray(n + 1)
        for (row in 1..n) {
            parent[0] = row
            var column = 0
            val minValue = LongArray(n + 1) { Long.MAX_VALUE / 4 }
            val used = BooleanArray(n + 1)
            do {
                used[column] = true
                val currentRow = parent[column]
                var delta = Long.MAX_VALUE / 4
                var nextColumn = 0
                for (candidate in 1..n) {
                    if (used[candidate]) continue
                    val reduced = cost[currentRow - 1][candidate - 1] - u[currentRow] - v[candidate]
                    if (reduced < minValue[candidate]) {
                        minValue[candidate] = reduced
                        way[candidate] = column
                    }
                    if (minValue[candidate] < delta) {
                        delta = minValue[candidate]
                        nextColumn = candidate
                    }
                }
                for (index in 0..n) {
                    if (used[index]) {
                        u[parent[index]] += delta
                        v[index] -= delta
                    } else {
                        minValue[index] -= delta
                    }
                }
                column = nextColumn
            } while (parent[column] != 0)
            do {
                val previous = way[column]
                parent[column] = parent[previous]
                column = previous
            } while (column != 0)
        }
        val result = IntArray(n) { -1 }
        for (column in 1..n) {
            if (parent[column] != 0) result[parent[column] - 1] = column - 1
        }
        return result
    }
}
