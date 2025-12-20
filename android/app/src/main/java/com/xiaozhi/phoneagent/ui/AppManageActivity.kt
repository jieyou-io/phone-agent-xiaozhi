package com.xiaozhi.phoneagent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaozhi.phoneagent.R
import com.xiaozhi.phoneagent.utils.AppLauncher
import com.xiaozhi.phoneagent.utils.PrefsManager

class AppManageActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val adapter = AppAdapter()
    private lateinit var prefs: PrefsManager

    private val addAppLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val appName = result.data?.getStringExtra("appName")
            val packageName = result.data?.getStringExtra("packageName")
            if (appName != null && packageName != null) {
                AppLauncher.addCustomApp(this, appName, packageName)
                refreshList()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_manage)

        prefs = PrefsManager(this)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            val intent = Intent(this, AppPickerActivity::class.java)
            addAppLauncher.launch(intent)
        }

        refreshList()
    }

    private fun refreshList() {
        val allApps = AppLauncher.getAllSupportedApps(this)
        adapter.items = allApps
        adapter.notifyDataSetChanged()
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        var items: List<String> = emptyList()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appName: TextView = view.findViewById(R.id.appName)
            val packageName: TextView = view.findViewById(R.id.packageName)
            val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
            val icon: ImageView = view.findViewById(R.id.icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = items[position]
            val pkg = AppLauncher.getPackageName(this@AppManageActivity, app)
            holder.appName.text = app
            holder.packageName.text = pkg

            val isCustom = prefs.customApps.containsKey(app)
            holder.btnDelete.visibility = if (isCustom) View.VISIBLE else View.GONE
            
            // Try to load icon
            try {
                val pm = packageManager
                val info = pm.getApplicationInfo(pkg ?: "", 0)
                holder.icon.setImageDrawable(info.loadIcon(pm))
            } catch (e: Exception) {
                holder.icon.setImageResource(R.drawable.ic_launcher_foreground)
            }

            holder.btnDelete.setOnClickListener {
                if (isCustom) {
                    AppLauncher.removeCustomApp(this@AppManageActivity, app)
                    refreshList()
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
