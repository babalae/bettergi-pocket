package com.bettergi.pocket.pathing.party

import android.content.res.AssetManager
import org.json.JSONArray

data class PartyAvatar(
    val name: String,
    val nameEn: String,
    val element: String?,
    val normalAttack: Boolean,
    val elementalSkill: Boolean,
    val skillCd: Double,
    val skillHoldCd: Double,
    val aliases: List<String> = emptyList(),
)

/** PC combat_avatar.json 的元素采集能力和战技冷却数据，不按武器类型猜测。 */
class AvatarCatalog(val avatars: List<PartyAvatar>, val labels: List<String>) {
    // 与 PC DefaultAutoFightConfig.CombatAvatarMap / CombatAvatarNameEnMap 相同，
    // 角色资料以 combat_avatar.json 为准；模型输出序号的顺序由模型 names 元数据决定。
    private val nameMap = avatars.associateBy { it.name }
    private val nameEnMap = avatars.associateBy { it.nameEn }

    init {
        require(nameMap.size == avatars.size && nameEnMap.size == avatars.size) { "角色映射配置包含重复名称" }
    }

    fun byName(name: String): PartyAvatar? = nameMap[name]

    /** 与 PC CombatScenes.ClassifyAvatarCnName 一致，衣装类别先去掉 Costume 后缀再查英文名映射。 */
    fun byLabel(label: String): PartyAvatar? = nameEnMap[label.substringBefore("Costume")]

    companion object {
        fun load(assets: AssetManager): AvatarCatalog {
            fun read(name: String) = assets.open("recognition/Party/$name").bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            return parse(read("combat_avatar.json"), read("avatar_labels.json"))
        }

        fun parse(avatarsJson: String, labelsJson: String): AvatarCatalog {
            val rows = JSONArray(avatarsJson)
            val labels = JSONArray(labelsJson)
            return AvatarCatalog((0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                PartyAvatar(row.getString("name"), row.getString("nameEn"),
                    row.optString("elementalType").takeIf { it.isNotBlank() },
                    row.optBoolean("normalAttack"), row.optBoolean("elementalSkill"),
                    row.optDouble("skillCD", 0.0), row.optDouble("skillHoldCD", 0.0),
                    row.optJSONArray("alias")?.let { aliases ->
                        (0 until aliases.length()).map { aliases.getString(it) }
                    } ?: emptyList())
            }, (0 until labels.length()).map { labels.getString(it) }).also {
                require(it.labels.size == 162) { "头像模型标签数量不匹配" }
                // 模型还包含派蒙、NPC、龙等训练类别；它们没有可操作的角色资料，识别时返回 null。
            }
        }
    }
}
