package com.example.treasurear.ui.subzone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.treasurear.data.db.AppDatabase
import com.example.treasurear.R
import com.example.treasurear.ui.model.UiItem
import com.example.treasurear.ui.pokedex.PokedexAdapter
import com.example.treasurear.ui.treasure.TreasureListActivity
import kotlinx.coroutines.launch

class SubZoneActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subzone)

        val zoneId = intent.getIntExtra("zoneId", -1)

        // ⭐ 도감(PokedexActivity)에서 받아온 zoneCode
        val zoneCode = intent.getStringExtra("zoneCode") ?: ""

        val recycler = findViewById<RecyclerView>(R.id.subZoneRecycler)
        recycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        lifecycleScope.launch {

            val items = buildList(zoneId)

            recycler.adapter = PokedexAdapter(items) { subZoneId ->

                // 현재 클릭된 SubZone 정보 찾기
                val selected = items
                    .filterIsInstance<UiItem.SubZone>()
                    .first { it.id == subZoneId }

                val intent = Intent(this@SubZoneActivity, TreasureListActivity::class.java)

                intent.putExtra("subZoneId", subZoneId)
                intent.putExtra("zoneCode", zoneCode)          // ⭐ 그냥 그대로 전달
                intent.putExtra("subZoneName", selected.title) // ⭐ subzone 전달

                startActivity(intent)
            }
        }
    }

    private suspend fun buildList(zoneId: Int): List<UiItem> {
        val dao = AppDatabase.getInstance(this).treasureDao()
        val subZones = dao.getSubZones(zoneId)

        return subZones.map { sub ->
            UiItem.SubZone(
                id = sub.id,
                title = sub.name,
                imageUrl = sub.imageUrl,
                totalCount = 0,
                collectedCount = 0
            )
        }
    }
}
