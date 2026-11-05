package com.bettergi.pocket.pathing.model

import com.bettergi.pocket.pathing.common.Point2f
import com.bettergi.pocket.pathing.map.FileMapAssetSource
import com.bettergi.pocket.pathing.map.MapManager
import com.bettergi.pocket.pathing.map.MapTypes
import com.bettergi.pocket.pathing.map.SceneMap
import com.bettergi.pocket.pathing.map.TeyvatMap
import com.bettergi.pocket.pathing.map.TeyvatTemplateMatchMap
import java.io.File
import org.json.JSONObject

/**
 * 动作及其强制点位类型。来源：BGI `ActionEnum` / `ActionUseWaypointTypeEnum`。
 * BGI 的 `Values` 枚举不完整，这里按类里声明的全部动作收录，供 `IsTargetPoint` 使用。
 */
enum class PathAction(
    val code: String,
    val label: String,
    val forcedType: ForcedPointType,
    val supported: Boolean,
) {
    StopFlying("stop_flying", "下落攻击", ForcedPointType.Custom, true),
    ForceTp("force_tp", "当前点传送", ForcedPointType.Custom, true),
    LogOutput("log_output", "输出日志", ForcedPointType.Custom, true),
    UseGadget("use_gadget", "使用小道具", ForcedPointType.Custom, true),
    NahidaCollect("nahida_collect", "纳西妲长按E收集", ForcedPointType.Custom, false),
    PickAround("pick_around", "尝试在周围拾取", ForcedPointType.Custom, false),
    Fight("fight", "战斗", ForcedPointType.Path, false),
    UpDownGrabLeaf("up_down_grab_leaf", "四叶印", ForcedPointType.Custom, false),
    HydroCollect("hydro_collect", "水元素力采集", ForcedPointType.Target, false),
    ElectroCollect("electro_collect", "雷元素力采集", ForcedPointType.Target, false),
    AnemoCollect("anemo_collect", "风元素力采集", ForcedPointType.Target, false),
    PyroCollect("pyro_collect", "火元素力采集", ForcedPointType.Target, false),
    CombatScript("combat_script", "战斗策略脚本", ForcedPointType.Custom, false),
    Mining("mining", "挖矿", ForcedPointType.Custom, false),
    LinneaMining("linnea_mining", "莉奈娅挖矿", ForcedPointType.Custom, false),
    Fishing("fishing", "钓鱼", ForcedPointType.Custom, false),
    ExitAndRelogin("exit_and_relogin", "退出重新登录", ForcedPointType.Custom, false),
    WonderlandCycle("wonderland_cycle", "进出千星奇域", ForcedPointType.Custom, false),
    SetTime("set_time", "设置时间", ForcedPointType.Custom, false),
    PickUpCollect("pick_up_collect", "聚集材料", ForcedPointType.Custom, false),
    ;

    companion object {
        fun fromCode(code: String?): PathAction? {
            if (code.isNullOrBlank()) return null
            return entries.firstOrNull { it.code == code }
        }
    }
}

enum class ForcedPointType {
    Custom,
    Path,
    Target,
}

enum class WaypointKind(val code: String) {
    Path("path"),
    Target("target"),
    Teleport("teleport"),
    Orientation("orientation"),
    ;

    companion object {
        fun fromCode(code: String?): WaypointKind {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: Path
        }
    }
}

enum class MoveMode(val code: String) {
    Walk("walk"),
    Run("run"),
    Dash("dash"),
    Climb("climb"),
    Fly("fly"),
    Jump("jump"),
    Swim("swim"),
    ;

    companion object {
        fun fromCode(code: String?): MoveMode {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: Walk
        }
    }
}

data class Misidentification(
    val types: List<String> = listOf("unrecognized"),
    val handlingMode: String = "previousDetectedPoint",
)

data class Waypoint(
    val gameX: Double,
    val gameY: Double,
    val kind: WaypointKind,
    val moveMode: MoveMode,
    val action: PathAction?,
    val actionCode: String?,
    val actionParams: String?,
    val misidentification: Misidentification,
)

data class WaypointForTrack(
    val gameX: Double,
    val gameY: Double,
    val imageX: Double,
    val imageY: Double,
    val kind: WaypointKind,
    val moveMode: MoveMode,
    val action: PathAction?,
    val actionCode: String?,
    val actionParams: String?,
    val misidentification: Misidentification,
    val mapName: String,
    val mapMatchMethod: String?,
)

data class PathingRoute(
    val name: String,
    val mapName: String,
    val mapMatchMethod: String?,
    val bgiVersion: String?,
    val waypoints: List<Waypoint>,
) {
    val segments: List<List<WaypointForTrack>> by lazy { PathingRoutes.segment(this) }
}

data class PathingResult(
    val isSuccess: Boolean,
    val errorMessage: String?,
)

object PathingRoutes {
    fun parse(json: String): PathingRoute {
        val root = JSONObject(json)
        val info = root.optJSONObject("info")
        val positions = root.optJSONArray("positions")
        val waypoints = ArrayList<Waypoint>()
        if (positions != null) {
            for (i in 0 until positions.length()) {
                waypoints += parseWaypoint(positions.getJSONObject(i))
            }
        }
        val method = info?.optString("map_match_method", "")?.takeIf { it.isNotBlank() }
        return PathingRoute(
            name = info?.optString("name").orEmpty(),
            mapName = info?.optString("map_name")?.takeIf { it.isNotBlank() } ?: MapTypes.Teyvat.name,
            mapMatchMethod = method,
            bgiVersion = info?.optString("bgi_version")?.takeIf { it.isNotBlank() },
            waypoints = waypoints,
        )
    }

    /**
     * 地图必须是提瓦特，且每个点的动作都在支持列表里。失败时给出原因，调用方整条拒绝。
     */
    fun rejectionReason(route: PathingRoute): String? {
        if (!route.mapName.equals(MapTypes.Teyvat.name, ignoreCase = true)) {
            return "只支持提瓦特，当前地图是 ${route.mapName}"
        }
        route.waypoints.forEachIndexed { index, waypoint ->
            val code = waypoint.actionCode
            if (code.isNullOrBlank()) return@forEachIndexed
            val action = PathAction.fromCode(code)
            if (action == null || !action.supported) {
                val name = action?.label ?: code
                return "第 ${index + 1} 个点的动作「$name」不支持，已拒绝整条路线"
            }
        }
        return null
    }

    fun segment(route: PathingRoute, map: SceneMap = defaultMap(route.mapMatchMethod)): List<List<WaypointForTrack>> {
        val tracked = route.waypoints.map { toTrack(it, route, map) }
        val result = ArrayList<ArrayList<WaypointForTrack>>()
        var current = ArrayList<WaypointForTrack>()
        for (waypoint in tracked) {
            if (waypoint.kind == WaypointKind.Teleport && current.isNotEmpty()) {
                result += current
                current = ArrayList()
            }
            current += waypoint
        }
        result += current
        return result
    }

    /** 来源：BGI `PathExecutor.IsTargetPoint`。 */
    fun isTargetPoint(waypoint: WaypointForTrack): Boolean {
        if (waypoint.kind == WaypointKind.Orientation || waypoint.action == PathAction.UpDownGrabLeaf) {
            return false
        }
        val action = waypoint.action
        if (action != null && action.forcedType != ForcedPointType.Custom) {
            return action.forcedType == ForcedPointType.Target
        }
        return waypoint.kind == WaypointKind.Target
    }

    private fun toTrack(waypoint: Waypoint, route: PathingRoute, map: SceneMap): WaypointForTrack {
        val image = map.convertGenshinMapCoordinatesToImageCoordinates(Point2f(waypoint.gameX.toFloat(), waypoint.gameY.toFloat()))
        return WaypointForTrack(
            gameX = waypoint.gameX,
            gameY = waypoint.gameY,
            imageX = image.x.toDouble(),
            imageY = image.y.toDouble(),
            kind = waypoint.kind,
            moveMode = waypoint.moveMode,
            action = waypoint.action,
            actionCode = waypoint.actionCode,
            actionParams = waypoint.actionParams,
            misidentification = waypoint.misidentification,
            mapName = route.mapName,
            mapMatchMethod = route.mapMatchMethod,
        )
    }

    private fun defaultMap(method: String?): SceneMap {
        val source = MapManager.assets ?: FileMapAssetSource(File("."))
        return if (method == MapManager.SIFT) TeyvatMap(source) else TeyvatTemplateMatchMap(source)
    }

    private fun parseWaypoint(json: JSONObject): Waypoint {
        val ext = json.optJSONObject("point_ext_params")
        val mis = ext?.optJSONObject("misidentification")
        // 写了 type 就照写的用（包括空数组），没写才用默认 ["unrecognized"]，与 BGI 反序列化一致
        val types = mis?.optJSONArray("type")?.let { array ->
            List(array.length()) { array.getString(it) }
        } ?: listOf("unrecognized")
        val actionCode = json.optString("action").takeIf { it.isNotBlank() }
        return Waypoint(
            gameX = json.optDouble("x"),
            gameY = json.optDouble("y"),
            kind = WaypointKind.fromCode(json.optString("type")),
            moveMode = MoveMode.fromCode(json.optString("move_mode")),
            action = PathAction.fromCode(actionCode),
            actionCode = actionCode,
            actionParams = json.optString("action_params").takeIf { it.isNotBlank() },
            misidentification = Misidentification(
                types = types,
                handlingMode = mis?.optString("handling_mode")?.takeIf { it.isNotBlank() } ?: "previousDetectedPoint",
            ),
        )
    }
}
