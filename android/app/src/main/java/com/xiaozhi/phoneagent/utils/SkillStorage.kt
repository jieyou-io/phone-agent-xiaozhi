package com.xiaozhi.phoneagent.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SkillStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    data class ModelConfig(
        val base_url: String? = null,
        val api_key: String? = null,
        val model: String? = null,
    )

    data class SubSkill(
        val id: String,
        val system_prompt: String,
        val model: ModelConfig? = null,
    )

    data class UserSkill(
        val id: String,
        val name: String,
        val description: String? = null,
        val system_prompt: String,
        val model: ModelConfig? = null,
        val skills: List<SubSkill> = emptyList(),
    )

    fun loadSkills(): List<UserSkill> {
        val json = prefs.getString(KEY_SKILLS, "[]") ?: "[]"
        val type = object : TypeToken<List<UserSkill>>() {}.type
        return try {
            val skills = gson.fromJson<List<UserSkill>>(json, type) ?: emptyList()
            // 数据迁移：为缺失description的旧数据生成默认值
            skills.map { skill ->
                val desc = skill.description?.trim() ?: ""
                if (desc.isEmpty()) {
                    skill.copy(description = "自定义技能:${skill.name}")
                } else {
                    skill.copy(description = desc)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSkill(skill: UserSkill) {
        val updated = loadSkills().toMutableList()
        updated.add(skill)
        save(updated)
    }

    fun upsertSkill(skill: UserSkill) {
        val updated = loadSkills().toMutableList()
        val index = updated.indexOfFirst { it.id == skill.id }
        if (index >= 0) {
            updated[index] = skill
        } else {
            updated.add(skill)
        }
        save(updated)
    }

    fun deleteSkill(skillId: String) {
        val updated = loadSkills().filterNot { it.id == skillId }
        save(updated)
    }

    fun toJson(skill: UserSkill): String {
        return gson.toJson(skill)
    }

    fun getBuiltinModels(): Map<String, ModelConfig> {
        val json = prefs.getString(KEY_BUILTIN_MODELS, "{}") ?: "{}"
        val type = object : TypeToken<Map<String, ModelConfig>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setBuiltinModel(skillId: String, model: ModelConfig?) {
        val current = getBuiltinModels().toMutableMap()
        if (model == null) {
            current.remove(skillId)
        } else {
            current[skillId] = model
        }
        prefs.edit().putString(KEY_BUILTIN_MODELS, gson.toJson(current)).apply()
    }

    private fun save(skills: List<UserSkill>) {
        prefs.edit().putString(KEY_SKILLS, gson.toJson(skills)).apply()
    }

    companion object {
        private const val PREFS_NAME = "skills_storage"
        private const val KEY_SKILLS = "skills"
        private const val KEY_BUILTIN_MODELS = "builtin_models"
    }
}
