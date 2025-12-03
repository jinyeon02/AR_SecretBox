package com.example.treasurear.ui.treasure

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.treasurear.data.db.AppDatabase
import com.example.treasurear.R
import kotlinx.coroutines.launch

class TreasureListActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_treasure_list)

        val subZoneId = intent.getIntExtra("subZoneId", -1)
        val zoneCode = intent.getStringExtra("zoneCode") ?: ""
        val subZoneName = intent.getStringExtra("subZoneName") ?: ""

        val title = findViewById<TextView>(R.id.tvTreasureHeaderTitle)
        val progress = findViewById<TextView>(R.id.tvTreasureHeaderProgress)
        val recycler = findViewById<RecyclerView>(R.id.treasureRecycler)

        title.text = "$zoneCode > $subZoneName"

        recycler.layoutManager = GridLayoutManager(this, 2)

        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@TreasureListActivity).treasureDao()
            val treasures = dao.getTreasures(subZoneId)

            progress.text = "${treasures.count { it.isCollected }} / ${treasures.size}"
            recycler.adapter = TreasureItemAdapter(treasures)
        }
        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        prefs.edit().putInt("lastSubZoneId", subZoneId).apply()

    }
}

