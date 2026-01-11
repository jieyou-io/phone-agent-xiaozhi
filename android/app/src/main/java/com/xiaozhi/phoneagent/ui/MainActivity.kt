package com.xiaozhi.phoneagent.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.xiaozhi.phoneagent.R
import com.xiaozhi.phoneagent.databinding.ActivityMainBinding
import com.xiaozhi.phoneagent.service.OverlayService
import com.xiaozhi.phoneagent.utils.HttpClient
import com.xiaozhi.phoneagent.utils.PrefsManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    private var homeFragment: HomeFragment? = null
    private var skillsFragment: SkillsFragment? = null
    private var settingsFragment: SettingsFragment? = null

    private val lifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            super.onStop(owner)
            if (Settings.canDrawOverlays(this@MainActivity) && prefs.showOverlay) {
                OverlayService.show(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)

        if (!HttpClient.hasCookie(prefs.backendUrl, "admin_session")) {
            Log.w(TAG, "No session cookie found, redirecting to login")
            navigateToLogin()
            return
        }

        validateSession()

        lifecycle.addObserver(lifecycleObserver)
        setupFragments(savedInstanceState)
        setupBottomNav()
        handleIntent(intent)
    }

    private fun validateSession() {
        val url = "${prefs.backendUrl}/api/auth/session"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Session validation network error: ${e.message}")
                // Network error - don't clear cookies, just log
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code == 401 || response.code == 403) {
                    Log.w(TAG, "Session invalid: ${response.code}")
                    runOnUiThread {
                        HttpClient.clearCookies()
                        navigateToLogin()
                    }
                } else if (!response.isSuccessful) {
                    Log.w(TAG, "Session validation failed: ${response.code}")
                } else {
                    Log.d(TAG, "Session valid")
                }
            }
        })
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            homeFragment = HomeFragment()
            skillsFragment = SkillsFragment()
            settingsFragment = SettingsFragment()

            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, homeFragment!!, TAG_HOME)
                .add(R.id.fragment_container, skillsFragment!!, TAG_SKILLS)
                .add(R.id.fragment_container, settingsFragment!!, TAG_SETTINGS)
                .hide(skillsFragment!!)
                .hide(settingsFragment!!)
                .commit()
        } else {
            homeFragment = supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment
            skillsFragment = supportFragmentManager.findFragmentByTag(TAG_SKILLS) as? SkillsFragment
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment

            if (homeFragment == null || skillsFragment == null || settingsFragment == null) {
                recoverMissingFragments()
            }
        }
    }

    /**
     * Recovers missing fragments after process death.
     *
     * IMPORTANT: This method uses commitNow() and must only be called during onCreate()
     * before savedInstanceState is saved. Calling after onSaveInstanceState() will crash.
     */
    private fun recoverMissingFragments() {
        val transaction = supportFragmentManager.beginTransaction()
        var needsRecovery = false

        if (homeFragment == null) {
            homeFragment = HomeFragment()
            transaction.add(R.id.fragment_container, homeFragment!!, TAG_HOME)
            needsRecovery = true
        }

        if (skillsFragment == null) {
            skillsFragment = SkillsFragment()
            transaction.add(R.id.fragment_container, skillsFragment!!, TAG_SKILLS)
            transaction.hide(skillsFragment!!)
            needsRecovery = true
        }

        if (settingsFragment == null) {
            settingsFragment = SettingsFragment()
            transaction.add(R.id.fragment_container, settingsFragment!!, TAG_SETTINGS)
            transaction.hide(settingsFragment!!)
            needsRecovery = true
        }

        if (needsRecovery) {
            transaction.commitNow()
        }
    }

    private fun setupBottomNav() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showFragment(when (item.itemId) {
                R.id.nav_home -> homeFragment
                R.id.nav_skills -> skillsFragment
                R.id.nav_settings -> settingsFragment
                else -> null
            })
            true
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun showFragment(fragment: Fragment?) {
        fragment ?: return
        val transaction = supportFragmentManager.beginTransaction()
        homeFragment?.let { if (it != fragment) transaction.hide(it) }
        skillsFragment?.let { if (it != fragment) transaction.hide(it) }
        settingsFragment?.let { if (it != fragment) transaction.hide(it) }
        transaction.show(fragment).commit()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == OverlayService.ACTION_QUICK_TASK) {
            handleQuickTaskIntent(intent)
            return
        }
        handleStartDestination(intent)
    }

    private fun handleQuickTaskIntent(intent: Intent) {
        val task = intent.getStringExtra(OverlayService.EXTRA_QUICK_TASK)
        if (!task.isNullOrEmpty()) {
            switchToHome()
            homeFragment?.processQuickTask(task)
        }
    }

    fun switchToHome() {
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    fun switchToSettings() {
        binding.bottomNavigation.selectedItemId = R.id.nav_settings
    }

    private fun handleStartDestination(intent: Intent) {
        val destination = intent.getStringExtra(EXTRA_START_DESTINATION) ?: return
        when (destination) {
            DEST_HOME -> binding.bottomNavigation.selectedItemId = R.id.nav_home
            DEST_SKILLS -> binding.bottomNavigation.selectedItemId = R.id.nav_skills
            DEST_SETTINGS -> binding.bottomNavigation.selectedItemId = R.id.nav_settings
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val TAG_HOME = "HOME"
        private const val TAG_SKILLS = "SKILLS"
        private const val TAG_SETTINGS = "SETTINGS"

        const val EXTRA_START_DESTINATION = "start_destination"
        const val DEST_HOME = "home"
        const val DEST_SKILLS = "skills"
        const val DEST_SETTINGS = "settings"
    }
}
