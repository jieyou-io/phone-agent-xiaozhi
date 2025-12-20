package com.xiaozhi.phoneagent.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaozhi.phoneagent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private val adapter = PickerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 } // Filter system apps? Maybe allow all. Let's filter to user apps for now to reduce clutter, or just non-system or launchable.
                .map { 
                    AppItem(
                        it.loadLabel(pm).toString(),
                        it.packageName,
                        it.loadIcon(pm)
                    )
                }
                .sortedBy { it.name }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.items = apps
                adapter.notifyDataSetChanged()
            }
        }
    }
    
    data class AppItem(val name: String, val packageName: String, val icon: android.graphics.drawable.Drawable)

    inner class PickerAdapter : RecyclerView.Adapter<PickerAdapter.ViewHolder>() {
        var items: List<AppItem> = emptyList()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appName: TextView = view.findViewById(R.id.appName)
            val packageName: TextView = view.findViewById(R.id.packageName)
            val icon: ImageView = view.findViewById(R.id.icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.appName.text = item.name
            holder.packageName.text = item.packageName
            holder.icon.setImageDrawable(item.icon)
            
            holder.itemView.setOnClickListener {
                val intent = Intent()
                intent.putExtra("appName", item.name)
                intent.putExtra("packageName", item.packageName)
                setResult(Activity.RESULT_OK, intent)
                finish()
            }
        }

        override fun getItemCount() = items.size
    }
}
