package com.xiaozhi.phoneagent.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xiaozhi.phoneagent.R
import com.xiaozhi.phoneagent.utils.HttpClient
import com.xiaozhi.phoneagent.utils.PrefsManager
import com.xiaozhi.phoneagent.utils.SkillStorage
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID

class SkillsFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SkillAdapter
    private lateinit var storage: SkillStorage
    private lateinit var prefs: PrefsManager
    private val gson = Gson()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_skills, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.skillsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SkillAdapter(
            onDelete = { skill ->
                if (skill.deletable) {
                    storage.deleteSkill(skill.id)
                    deleteRemoteSkill(skill.id)
                    refreshSkills()
                }
            },
            onEdit = { skill ->
                val userSkill = skill.userSkill
                if (userSkill != null) {
                    showAddDialog(userSkill)
                } else {
                    showBuiltinModelDialog(skill.id, skill.name)
                }
            },
        )
        recyclerView.adapter = adapter

        storage = SkillStorage(requireContext())
        prefs = PrefsManager(requireContext())

        view.findViewById<FloatingActionButton>(R.id.addSkillFab).setOnClickListener {
            showAddDialog()
        }

        refreshSkills()
    }

    private fun showAddDialog(existing: SkillStorage.UserSkill? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_add_skill, null)
        val nameInput = view.findViewById<EditText>(R.id.inputSkillName)
        val descriptionInput = view.findViewById<EditText>(R.id.inputSkillDescription)
        val promptInput = view.findViewById<EditText>(R.id.inputSkillPrompt)
        val baseUrlInput = view.findViewById<EditText>(R.id.inputSkillBaseUrl)
        val apiKeyInput = view.findViewById<EditText>(R.id.inputSkillApiKey)
        val modelInput = view.findViewById<EditText>(R.id.inputSkillModel)
        val addSubButton = view.findViewById<Button>(R.id.buttonAddSubSkill)
        val container = view.findViewById<LinearLayout>(R.id.subSkillContainer)

        addSubButton.setOnClickListener {
            val row = layoutInflater.inflate(R.layout.item_sub_skill, container, false)
            row.findViewById<Button>(R.id.buttonRemoveSubSkill).setOnClickListener {
                container.removeView(row)
            }
            container.addView(row)
        }

        if (existing != null) {
            nameInput.setText(existing.name)
            descriptionInput.setText(existing.description ?: "")
            promptInput.setText(existing.system_prompt)
            baseUrlInput.setText(existing.model?.base_url ?: "")
            apiKeyInput.setText(existing.model?.api_key ?: "")
            modelInput.setText(existing.model?.model ?: "")
            for (sub in existing.skills) {
                val row = layoutInflater.inflate(R.layout.item_sub_skill, container, false)
                row.findViewById<EditText>(R.id.inputSubSkillName).setText(sub.id)
                row.findViewById<EditText>(R.id.inputSubSkillPrompt).setText(sub.system_prompt)
                row.findViewById<EditText>(R.id.inputSubSkillBaseUrl).setText(sub.model?.base_url ?: "")
                row.findViewById<EditText>(R.id.inputSubSkillApiKey).setText(sub.model?.api_key ?: "")
                row.findViewById<EditText>(R.id.inputSubSkillModel).setText(sub.model?.model ?: "")
                row.findViewById<Button>(R.id.buttonRemoveSubSkill).setOnClickListener {
                    container.removeView(row)
                }
                container.addView(row)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_skill)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text.toString().trim()
                val description = descriptionInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                if (name.isEmpty() || description.isEmpty() || prompt.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.skill_form_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val model = buildModelConfig(
                    baseUrlInput.text.toString().trim(),
                    apiKeyInput.text.toString().trim(),
                    modelInput.text.toString().trim(),
                )

                val subSkills = mutableListOf<SkillStorage.SubSkill>()
                for (i in 0 until container.childCount) {
                    val row = container.getChildAt(i)
                    val subName = row.findViewById<EditText>(R.id.inputSubSkillName).text.toString().trim()
                    val subPrompt = row.findViewById<EditText>(R.id.inputSubSkillPrompt).text.toString().trim()
                    val subBaseUrl = row.findViewById<EditText>(R.id.inputSubSkillBaseUrl).text.toString().trim()
                    val subApiKey = row.findViewById<EditText>(R.id.inputSubSkillApiKey).text.toString().trim()
                    val subModelName = row.findViewById<EditText>(R.id.inputSubSkillModel).text.toString().trim()

                    if (subPrompt.isEmpty()) continue
                    val subId = if (subName.isNotEmpty()) subName else "sub_${UUID.randomUUID()}"
                    val subModel = buildModelConfig(subBaseUrl, subApiKey, subModelName)
                    subSkills.add(SkillStorage.SubSkill(subId, subPrompt, subModel))
                }

                val id = existing?.id ?: "user_${UUID.randomUUID()}"
                val skill = SkillStorage.UserSkill(
                    id = id,
                    name = name,
                    description = description,
                    system_prompt = prompt,
                    model = model,
                    skills = subSkills,
                )
                storage.upsertSkill(skill)
                syncUserSkill(skill)
                refreshSkills()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBuiltinModelDialog(skillId: String, skillName: String) {
        val view = layoutInflater.inflate(R.layout.dialog_model_config, null)
        val modelTypeGroup = view.findViewById<android.widget.RadioGroup>(R.id.modelTypeGroup)
        val radioDefault = view.findViewById<android.widget.RadioButton>(R.id.radioDefaultModel)
        val radioCustom = view.findViewById<android.widget.RadioButton>(R.id.radioCustomModel)
        val customContainer = view.findViewById<View>(R.id.customModelContainer)

        val baseUrlInput = view.findViewById<EditText>(R.id.inputModelBaseUrl)
        val apiKeyInput = view.findViewById<EditText>(R.id.inputModelApiKey)
        val modelInput = view.findViewById<EditText>(R.id.inputModelName)

        val current = storage.getBuiltinModels()[skillId]

        // Initialize state
        if (current == null) {
            radioDefault.isChecked = true
            customContainer.visibility = View.GONE
        } else {
            radioCustom.isChecked = true
            customContainer.visibility = View.VISIBLE
            baseUrlInput.setText(current.base_url ?: "")
            apiKeyInput.setText(current.api_key ?: "")
            modelInput.setText(current.model ?: "")
        }

        // Handle toggle
        modelTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioDefaultModel) {
                customContainer.visibility = View.GONE
            } else {
                customContainer.visibility = View.VISIBLE
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.skill_model_title, skillName))
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                if (radioDefault.isChecked) {
                    storage.setBuiltinModel(skillId, null)
                } else {
                    val baseUrl = baseUrlInput.text.toString().trim()
                    val apiKey = apiKeyInput.text.toString().trim()
                    val modelName = modelInput.text.toString().trim()

                    if (baseUrl.isEmpty() || apiKey.isEmpty() || modelName.isEmpty()) {
                        Toast.makeText(requireContext(), "请填写完整的模型配置", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val model = buildModelConfig(baseUrl, apiKey, modelName)
                    storage.setBuiltinModel(skillId, model)
                }
                refreshSkills()
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                storage.setBuiltinModel(skillId, null)
                refreshSkills()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildModelConfig(baseUrl: String, apiKey: String, modelName: String): SkillStorage.ModelConfig? {
        if (baseUrl.isEmpty() && apiKey.isEmpty() && modelName.isEmpty()) return null
        return SkillStorage.ModelConfig(
            base_url = if (baseUrl.isEmpty()) null else baseUrl,
            api_key = if (apiKey.isEmpty()) null else apiKey,
            model = if (modelName.isEmpty()) null else modelName,
        )
    }

    private fun refreshSkills() {
        val local = storage.loadSkills().map {
            SkillItem(it.id, it.name, it.description ?: "", deletable = true, userSkill = it, isBuiltin = false)
        }
        adapter.submit(local)
        fetchBuiltinSkills { builtins ->
            val combined = builtins + local
            adapter.submit(combined)
        }
    }

    private fun fetchBuiltinSkills(onDone: (List<SkillItem>) -> Unit) {
        val builtinModels = storage.getBuiltinModels()

        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) {
            Log.w(TAG, "Backend URL missing, skip skills fetch")
            activity?.runOnUiThread { onDone(emptyList()) }
            return
        }

        val deviceId = prefs.deviceId
        val finalUrl = "$backendUrl/api/skills?device_id=$deviceId"
        Log.d(TAG, "Fetching skills from $finalUrl")

        val request = Request.Builder().url(finalUrl).get().build()
        Thread {
            try {
                HttpClient.get().newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        Log.e(TAG, "Skills fetch failed: ${response.code} ${response.message} body=$body")
                        activity?.runOnUiThread { onDone(emptyList()) }
                        return@use
                    }

                    val root = gson.fromJson(body, JsonElement::class.java)
                    if (root == null || !root.isJsonArray) {
                        Log.e(TAG, "Unexpected skills response: $body")
                        activity?.runOnUiThread { onDone(emptyList()) }
                        return@use
                    }

                    val jsonArray = root.asJsonArray
                    val skills = jsonArray.mapNotNull { item ->
                        val obj = item.asJsonObject
                        val id = obj.get("id")?.asString ?: return@mapNotNull null
                        val isBuiltin = obj.get("is_builtin")?.asBoolean ?: false
                        if (!isBuiltin) return@mapNotNull null
                        val name = obj.get("name")?.asString ?: id
                        val baseDesc = obj.get("description")?.asString ?: ""
                        val modelHint = builtinModels[id]?.model?.let { "模型: $it" }
                        val desc = if (!modelHint.isNullOrEmpty()) "$baseDesc\n$modelHint" else baseDesc
                        SkillItem(id, name, desc, deletable = false, userSkill = null, isBuiltin = true)
                    }
                    Log.d(TAG, "Fetched ${skills.size} builtin skills")
                    activity?.runOnUiThread { onDone(skills) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Skills fetch error", e)
                activity?.runOnUiThread { onDone(emptyList()) }
            }
        }.start()
    }

    data class SkillItem(
        val id: String,
        val name: String,
        val description: String,
        val deletable: Boolean,
        val userSkill: SkillStorage.UserSkill?,
        val isBuiltin: Boolean,
    )

    private fun isLocalSkillId(skillId: String): Boolean {
        return skillId.startsWith("user_")
    }

    private fun syncUserSkill(skill: SkillStorage.UserSkill) {
        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) {
            Log.w(TAG, "Backend URL missing, skip user skill sync")
            return
        }

        val deviceId = prefs.deviceId
        val definition = JsonObject().apply {
            addProperty("system_prompt", skill.system_prompt)
            if (skill.model != null) {
                add("model", gson.toJsonTree(skill.model))
            }
            if (skill.skills.isNotEmpty()) {
                add("skills", gson.toJsonTree(skill.skills))
            }
        }

        val payload = JsonObject().apply {
            addProperty("name", skill.name)
            addProperty("description", skill.description ?: "")
            add("definition", definition)
            if (isLocalSkillId(skill.id)) {
                addProperty("owner_device_id", deviceId)
            }
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = if (isLocalSkillId(skill.id)) {
            Request.Builder()
                .url("$backendUrl/api/skills")
                .post(requestBody)
                .build()
        } else {
            Request.Builder()
                .url("$backendUrl/api/skills/${skill.id}?device_id=$deviceId")
                .put(requestBody)
                .build()
        }

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "User skill sync failed", e)
                showToast(getString(R.string.skill_sync_failed))
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "User skill sync failed: ${response.code} ${response.message} body=$body")
                    showToast(getString(R.string.skill_sync_failed))
                    return
                }

                if (isLocalSkillId(skill.id)) {
                    val created = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                    val remoteId = created?.get("id")?.asString
                    if (!remoteId.isNullOrBlank() && remoteId != skill.id) {
                        storage.deleteSkill(skill.id)
                        storage.upsertSkill(skill.copy(id = remoteId))
                        activity?.runOnUiThread { refreshSkills() }
                    }
                }
            }
        })
    }

    private fun deleteRemoteSkill(skillId: String) {
        if (isLocalSkillId(skillId)) return

        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) {
            Log.w(TAG, "Backend URL missing, skip delete sync")
            return
        }

        val deviceId = prefs.deviceId
        val request = Request.Builder()
            .url("$backendUrl/api/skills/$skillId?device_id=$deviceId")
            .delete()
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "User skill delete sync failed", e)
                showToast(getString(R.string.skill_delete_sync_failed))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "User skill delete sync failed: ${response.code} ${response.message}")
                    showToast(getString(R.string.skill_delete_sync_failed))
                }
            }
        })
    }

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    class SkillAdapter(
        private val onDelete: (SkillItem) -> Unit,
        private val onEdit: (SkillItem) -> Unit,
    ) : RecyclerView.Adapter<SkillAdapter.ViewHolder>() {
        private val items = mutableListOf<SkillItem>()

        fun submit(list: List<SkillItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_skill, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.skillId.text = item.id
            holder.description.text = item.description
            holder.deleteButton.visibility = if (item.deletable) View.VISIBLE else View.GONE
            holder.configButton.visibility = if (item.isBuiltin) View.VISIBLE else View.GONE
            holder.deleteButton.setOnClickListener { onDelete(item) }
            holder.configButton.setOnClickListener { onEdit(item) }
            holder.itemView.setOnClickListener {
                onEdit(item)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.skillName)
            val skillId: TextView = view.findViewById(R.id.skillId)
            val description: TextView = view.findViewById(R.id.skillDescription)
            val deleteButton: Button = view.findViewById(R.id.deleteButton)
            val configButton: MaterialButton = view.findViewById(R.id.configButton)
        }
    }

    companion object {
        private const val TAG = "SkillsFragment"
    }
}
