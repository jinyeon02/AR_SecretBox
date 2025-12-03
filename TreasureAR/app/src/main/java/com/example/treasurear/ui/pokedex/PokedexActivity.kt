package com.example.treasurear.ui.pokedex

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.example.treasurear.R
import com.example.treasurear.data.dao.TreasureDao
import com.example.treasurear.data.db.AppDatabase
import com.example.treasurear.data.entity.ZoneEntity
import com.example.treasurear.ui.model.UiItem
import com.example.treasurear.ui.treasure.TreasureListActivity
import kotlinx.coroutines.launch

class PokedexActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var tabContainer: LinearLayout
    private lateinit var txtProgress: TextView

    private lateinit var adapter: PokedexAdapter
    private val indexMap = mutableMapOf<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pokedex)

        recycler = findViewById(R.id.pokedexRv)
        tabContainer = findViewById(R.id.tabContainer)
        txtProgress = findViewById(R.id.pokedexProgress)

        // 1. [경고 해결] 레이아웃 매니저 및 빈 어댑터 미리 연결
        val grid = GridLayoutManager(this, 3)
        recycler.layoutManager = grid

        adapter = PokedexAdapter(emptyList()) { }
        recycler.adapter = adapter

        // SpanSizeLookup 설정
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position >= adapter.itemCount) return 1
                return if (adapter.getItemViewType(position) == 0) 3 else 1
            }
        }

        // 스크롤 리스너 (탭 하이라이트) - 한 번만 등록
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                val layout = rv.layoutManager as GridLayoutManager
                val first = layout.findFirstVisibleItemPosition()

                if (first != RecyclerView.NO_POSITION && first < adapter.publicItems.size) {
                    val item = adapter.publicItems[first]
                    if (item is UiItem.Header) {
                        highlightTab(item.title)
                    }
                }
            }
        })
    }

    // 2. [자동 갱신] 화면이 보일 때마다 데이터를 새로 불러옴
    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@PokedexActivity).treasureDao()
            val zones = dao.getZones()

            // DB에서 최신 데이터(수집 상태 포함) 가져오기
            val items = buildList(zones)

            // 3. 어댑터 데이터 교체
            adapter = PokedexAdapter(items) { subZoneId ->
                val selected = items.filterIsInstance<UiItem.SubZone>()
                    .firstOrNull { it.id == subZoneId } ?: return@PokedexAdapter

                val index = items.indexOf(selected)
                val header = items.subList(0, index)
                    .lastOrNull { it is UiItem.Header } as? UiItem.Header

                if (header != null) {
                    val intent = Intent(this@PokedexActivity, TreasureListActivity::class.java).apply {
                        putExtra("subZoneId", subZoneId)
                        putExtra("zoneCode", header.title)
                        putExtra("subZoneName", selected.title)
                    }
                    startActivity(intent)
                }
            }

            // 리사이클러뷰에 갱신된 어댑터 장착
            recycler.adapter = adapter

            // 탭 및 진행률 갱신
            createTabs(zones)
            updateTotalProgress(dao)
        }
    }

    private suspend fun updateTotalProgress(dao: TreasureDao) {
        val total = dao.getTotalTreasureCount()
        val collected = dao.getTotalCollectedCount()
        txtProgress.text = "$collected / $total"
    }

    private suspend fun buildList(zones: List<ZoneEntity>): List<UiItem> {
        val list = mutableListOf<UiItem>()
        val dao = AppDatabase.getInstance(this).treasureDao()

        for (zone in zones) {
            indexMap[zone.code] = list.size
            list.add(UiItem.Header(zone.code))

            val subZones = dao.getSubZones(zone.id)
            for (sub in subZones) {
                val total = dao.getTreasureCount(sub.id)
                val collected = dao.getCollectedCountBySubZone(sub.id)

                list.add(
                    UiItem.SubZone(
                        id = sub.id,
                        title = sub.name,
                        imageUrl = sub.imageUrl,
                        totalCount = total,
                        collectedCount = collected
                    )
                )
            }
        }
        return list
    }

    private fun createTabs(zones: List<ZoneEntity>) {
        tabContainer.removeAllViews()

        zones.forEach { zone ->
            val tab = TextView(this).apply {
                text = zone.code
                setPadding(40, 20, 40, 20)
                setTextColor(Color.parseColor("#BBBBBB"))

                setOnClickListener {
                    val target = indexMap[zone.code] ?: 0
                    smoothSnapToPosition(target)
                    highlightTab(zone.code)
                }
            }
            tabContainer.addView(tab)
        }

        if (zones.isNotEmpty()) highlightTab(zones.first().code)
    }

    private fun highlightTab(code: String) {
        for (i in 0 until tabContainer.childCount) {
            val t = tabContainer.getChildAt(i) as TextView

            if (t.text == code) {
                t.setTextColor(Color.WHITE)
                t.setBackgroundColor(Color.parseColor("#36C291"))
            } else {
                t.setTextColor(Color.parseColor("#BBBBBB"))
                t.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun smoothSnapToPosition(position: Int) {
        val smoothScroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START
        }
        smoothScroller.targetPosition = position
        recycler.layoutManager?.startSmoothScroll(smoothScroller)
    }
}