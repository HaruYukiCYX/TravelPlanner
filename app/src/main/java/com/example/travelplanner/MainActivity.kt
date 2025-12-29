package com.harukayuki.travelplanner

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.*
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.harukayuki.travelplanner.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), Inputtips.InputtipsListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var aMap: AMap
    private lateinit var db: AppDatabase
    private lateinit var tripAdapter: TripAdapter
    private var allTrips = mutableListOf<Trip>()
    private var segmentsData = mutableMapOf<Int, List<Segment>>()
    private lateinit var suggestionAdapter: SuggestionAdapter

    // 状态变量
    private var searchMode: Int = 0 // 0:全局搜索, 1:规划起点, 2:规划终点
    private var isSearchingStart: Boolean = true // 辅助判断
    private var startLatLon: LatLonPoint? = null
    private var endLatLon: LatLonPoint? = null
    private var activeTripId: Int = -1

    private val transportOptions = arrayOf("汽车", "飞机", "高铁", "火车", "自行车", "地铁")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 高德隐私合规
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)
        com.amap.api.maps.MapsInitializer.updatePrivacyShow(this, true, true)
        com.amap.api.maps.MapsInitializer.updatePrivacyAgree(this, true)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.onCreate(savedInstanceState)
        aMap = binding.mapView.map
        val uiSettings = aMap.uiSettings
        uiSettings.isZoomControlsEnabled = false
        try { aMap.showBuildings(true) } catch (e: Exception) {}

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "travel_final_v14")
            .fallbackToDestructiveMigration().build()

        setupTransportMenu()
        setupRecyclerViews()
        initInteractions()
        loadAllData()
    }

    private fun setupTransportMenu() {
        val autoComplete = findViewById<AutoCompleteTextView>(R.id.autoCompleteTransport)
        autoComplete.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, transportOptions))
        autoComplete.setText(transportOptions[0], false)
    }

    private fun setupRecyclerViews() {
        // 侧边栏适配器
        tripAdapter = TripAdapter(
            onHeaderClick = { trip ->
                trip.isExpanded = !trip.isExpanded
                activeTripId = trip.id
                refreshSidebarAndMap()
            },
            onHeaderLongClick = { trip -> showEditTripNameDialog(trip) },
            onFavoriteClick = { toggleFavorite(it) },
            onSegmentClick = { segment, isLast ->
                if (isLast) {
                    activeTripId = segment.tripId
                    BottomSheetBehavior.from(findViewById(R.id.bottomSheet)).state = BottomSheetBehavior.STATE_EXPANDED
                    findViewById<EditText>(R.id.etStart).setText(segment.endLoc)
                    startLatLon = LatLonPoint(segment.endLat, segment.endLng)
                }
            },
            onDeleteTrip = { trip -> deleteTrip(trip) }
        )
        binding.rvItineraries.layoutManager = LinearLayoutManager(this)
        binding.rvItineraries.adapter = tripAdapter

        // 搜索建议适配器
        suggestionAdapter = SuggestionAdapter { tip -> handleSuggestionClick(tip) }

        // 全局建议列表
        binding.rvGlobalSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvGlobalSuggestions.adapter = suggestionAdapter

        // 规划面板建议列表
        findViewById<RecyclerView>(R.id.rvSuggestions).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = suggestionAdapter
        }
    }

    // 覆盖 MainActivity.kt 中的 handleSuggestionClick 方法
    // 覆盖 MainActivity.kt 中的 handleSuggestionClick 方法
    private fun handleSuggestionClick(tip: Tip) {
        if (tip.point == null) return

        // 获取布局中的容器引用
        val cardSuggestions = findViewById<View>(R.id.cardSuggestions)
        val layoutDetails = findViewById<View>(R.id.layoutDetails)
        val etStart = findViewById<EditText>(R.id.etStart)
        val etEnd = findViewById<EditText>(R.id.etEnd)

        when (searchMode) {
            0 -> { // 全局探索模式逻辑 (保持之前的逻辑)
                aMap.clear()
                val marker = aMap.addMarker(MarkerOptions()
                    .position(LatLng(tip.point.latitude, tip.point.longitude))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)))
                aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 15f))

                findViewById<TextView>(R.id.tvResultName).text = tip.name
                findViewById<TextView>(R.id.tvResultAddress).text = tip.address ?: "暂无详细地址"
                findViewById<View>(R.id.cardSearchResult).visibility = View.VISIBLE
                binding.btnAdd.visibility = View.GONE

                findViewById<Button>(R.id.btnStartPlanWithThis).setOnClickListener {
                    activeTripId = -1
                    clearAllInputs()
                    findViewById<EditText>(R.id.etEnd).setText(tip.name)
                    endLatLon = tip.point
                    findViewById<View>(R.id.cardSearchResult).visibility = View.GONE
                    binding.btnAdd.visibility = View.VISIBLE
                    BottomSheetBehavior.from(findViewById(R.id.bottomSheet)).state = BottomSheetBehavior.STATE_EXPANDED
                }
                exitSearchMode()
            }

            1 -> { // 规划模式 - 设置起点
                etStart.setText(tip.name)
                startLatLon = tip.point

                // --- 核心改动：选中后收起列表，恢复下方表单 ---
                cardSuggestions.visibility = View.GONE
                layoutDetails.visibility = View.VISIBLE

                // 清除输入框焦点，防止软键盘挡住下方的表单
                etStart.clearFocus()
                // 可选：如果用户选完起点，自动让终点输入框获取焦点，提升连贯性
                if (etEnd.text.isEmpty()) {
                    etEnd.requestFocus()
                }
            }

            2 -> { // 规划模式 - 设置终点
                etEnd.setText(tip.name)
                endLatLon = tip.point

                // --- 核心改动：选中后收起列表，恢复下方表单 ---
                cardSuggestions.visibility = View.GONE
                layoutDetails.visibility = View.VISIBLE

                etEnd.clearFocus()
            }
        }
    }

    private fun initInteractions() {
        // 1. 获取底部面板的行为控制对象
        val sheetRoot = findViewById<View>(R.id.bottomSheet)
        val sheetBehavior = BottomSheetBehavior.from(sheetRoot)

        // 2. 顶部搜索触发按钮逻辑
        binding.btnSearchTrigger.setOnClickListener {
            // --- 核心改动：回收底部面板 ---
            if (sheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
                sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }

            // 如果搜索结果卡片正显示，也先把它藏起来
            findViewById<View>(R.id.cardSearchResult).visibility = View.GONE
            binding.btnAdd.visibility = View.VISIBLE

            // 进入搜索模式
            binding.layoutTitle.visibility = View.GONE
            binding.layoutSearchInput.visibility = View.VISIBLE
            binding.etGlobalSearch.requestFocus()
            searchMode = 0
        }

        binding.btnAdd.setOnClickListener {
            exitSearchMode()
            activeTripId = -1
            clearAllInputs()
            sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        binding.btnSearchBack.setOnClickListener { exitSearchMode() }
        binding.btnSearchClear.setOnClickListener { binding.etGlobalSearch.text.clear() }

        binding.etGlobalSearch.addTextChangedListener(createWatcher {
            if(it.isNotEmpty()) {
                requestTips(it)
                binding.cardGlobalSuggestions.visibility = View.VISIBLE
            } else {
                binding.cardGlobalSuggestions.visibility = View.GONE
            }
        })

        binding.btnMenu.setOnClickListener { binding.drawerLayout.open() }

        val etStart = findViewById<EditText>(R.id.etStart)
        val etEnd = findViewById<EditText>(R.id.etEnd)
        etStart.addTextChangedListener(createWatcher {
            isSearchingStart = true; searchMode = 1; requestTips(it)
            findViewById<View>(R.id.cardSuggestions).visibility = View.VISIBLE
            findViewById<View>(R.id.layoutDetails).visibility = View.GONE
        })
        etEnd.addTextChangedListener(createWatcher {
            isSearchingStart = false; searchMode = 2; requestTips(it)
            findViewById<View>(R.id.cardSuggestions).visibility = View.VISIBLE
            findViewById<View>(R.id.layoutDetails).visibility = View.GONE
        })

        findViewById<Button>(R.id.btnDone).setOnClickListener {
            val trans = findViewById<AutoCompleteTextView>(R.id.autoCompleteTransport).text.toString()
            val price = findViewById<EditText>(R.id.etPrice).text.toString()
            if (startLatLon != null && endLatLon != null) saveNewSegment(etStart.text.toString(), etEnd.text.toString(), trans, price)
            else Toast.makeText(this, "请选择地点", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exitSearchMode() {
        binding.layoutSearchInput.visibility = View.GONE
        binding.layoutTitle.visibility = View.VISIBLE
        binding.etGlobalSearch.text.clear()
        binding.cardGlobalSuggestions.visibility = View.GONE
        binding.etGlobalSearch.clearFocus()
    }

    private fun toggleFavorite(trip: Trip) {
        trip.isFavorite = !trip.isFavorite
        lifecycleScope.launch(Dispatchers.IO) {
            db.travelDao().updateTrip(trip)
            val newTripsFromDb = db.travelDao().getAllTrips()
            newTripsFromDb.forEach { nt ->
                nt.isExpanded = allTrips.find { it.id == nt.id }?.isExpanded ?: false
            }
            withContext(Dispatchers.Main) {
                allTrips.clear()
                allTrips.addAll(newTripsFromDb)
                refreshSidebarAndMap()
            }
        }
    }

    private fun refreshSidebarAndMap() {
        tripAdapter.updateData(allTrips, segmentsData)
        aMap.clear()
        val activeSegments = segmentsData[activeTripId] ?: emptyList()
        if (activeSegments.isNotEmpty()) {
            val boundsBuilder = LatLngBounds.Builder()
            activeSegments.forEach { seg ->
                val pStart = LatLng(seg.startLat, seg.startLng)
                val pEnd = LatLng(seg.endLat, seg.endLng)
                val curvePoints = getCurvePoints(pStart, pEnd)

                // 生长动画
                drawAnimatedLine(curvePoints)

                // 气泡
                val customView = createCustomMarkerView(seg.transport, seg.price)
                aMap.addMarker(MarkerOptions().position(curvePoints[curvePoints.size/2]).icon(BitmapDescriptorFactory.fromView(customView)).anchor(0.5f, 1.0f))

                // 起终点大头针
                aMap.addMarker(MarkerOptions().position(pStart).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)).title("起点: ${seg.startLoc}"))
                aMap.addMarker(MarkerOptions().position(pEnd).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).title("终点: ${seg.endLoc}"))

                boundsBuilder.include(pStart).include(pEnd)
            }
            try { aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 250)) } catch (e: Exception) {}
        }
    }

    private fun createCustomMarkerView(transport: String, price: String): View {
        val view = layoutInflater.inflate(R.layout.custom_marker_view, null)
        val icon = view.findViewById<ImageView>(R.id.ivMarkerIcon)
        val tv = view.findViewById<TextView>(R.id.tvMarkerPrice)
        tv.text = "¥$price"
        val resId = when (transport.trim()) {
            "飞机" -> R.drawable.ic_plane
            "高铁" -> R.drawable.ic_railway
            "汽车" -> R.drawable.ic_car
            "自行车" -> R.drawable.ic_bike
            "火车" -> R.drawable.ic_train
            "地铁" -> R.drawable.ic_metro
            else -> android.R.drawable.ic_menu_compass
        }
        icon.setImageResource(resId)
        return view
    }

    private fun drawAnimatedLine(points: List<LatLng>) {
        val polyline = aMap.addPolyline(PolylineOptions().width(18f).color(Color.parseColor("#2196F3")).lineJoinType(PolylineOptions.LineJoinType.LineJoinRound))
        lifecycleScope.launch {
            val current = mutableListOf<LatLng>()
            for (p in points) { current.add(p); polyline.points = current; delay(15) }
        }
    }

    private fun getCurvePoints(start: LatLng, end: LatLng): List<LatLng> {
        val points = mutableListOf<LatLng>(); val count = 30
        val midLat = (start.latitude + end.latitude) / 2; val midLng = (start.longitude + end.longitude) / 2
        val distance = Math.sqrt(Math.pow(start.latitude - end.latitude, 2.0) + Math.pow(start.longitude - end.longitude, 2.0))
        val controlPoint = LatLng(midLat + (distance * 0.15), midLng)
        for (i in 0..count) {
            val t = i.toDouble() / count
            val lat = (1 - t) * (1 - t) * start.latitude + 2 * (1 - t) * t * controlPoint.latitude + t * t * end.latitude
            val lng = (1 - t) * (1 - t) * start.longitude + 2 * (1 - t) * t * controlPoint.longitude + t * t * end.longitude
            points.add(LatLng(lat, lng))
        }
        return points
    }

    private fun saveNewSegment(sName: String, eName: String, trans: String, price: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (activeTripId == -1) activeTripId = db.travelDao().insertTrip(Trip(tripName = "我的新计划")).toInt()
            db.travelDao().insertSegment(Segment(tripId = activeTripId, startLoc = sName, endLoc = eName, startLat = startLatLon!!.latitude, startLng = startLatLon!!.longitude, endLat = endLatLon!!.latitude, endLng = endLatLon!!.longitude, transport = trans, price = price))
            loadAllData()
            withContext(Dispatchers.Main) {
                BottomSheetBehavior.from(findViewById(R.id.bottomSheet)).state = BottomSheetBehavior.STATE_COLLAPSED
                clearAllInputs()
            }
        }
    }

    private fun loadAllData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val trips = db.travelDao().getAllTrips()
            val tempMap = mutableMapOf<Int, List<Segment>>()
            trips.forEach { nt ->
                nt.isExpanded = allTrips.find { it.id == nt.id }?.isExpanded ?: false
                tempMap[nt.id] = db.travelDao().getSegmentsByTrip(nt.id)
            }
            withContext(Dispatchers.Main) {
                allTrips.clear(); allTrips.addAll(trips); segmentsData.clear(); segmentsData.putAll(tempMap)
                refreshSidebarAndMap()
            }
        }
    }

    private fun showEditTripNameDialog(trip: Trip) {
        val et = EditText(this).apply { setText(trip.tripName) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("改名").setView(et).setPositiveButton("确定") { _, _ ->
            val n = et.text.toString().trim()
            if (n.isNotEmpty()) { trip.tripName = n; lifecycleScope.launch(Dispatchers.IO) { db.travelDao().updateTrip(trip); loadAllData() } }
        }.show()
    }

    private fun deleteTrip(trip: Trip) { lifecycleScope.launch(Dispatchers.IO) { db.travelDao().deleteSegmentsByTrip(trip.id); db.travelDao().deleteTrip(trip); loadAllData() } }
    private fun requestTips(kw: String) { Inputtips(this, InputtipsQuery(kw, "")).apply { setInputtipsListener(this@MainActivity); requestInputtipsAsyn() } }
    override fun onGetInputtips(p0: MutableList<Tip>?, p1: Int) {
        if (p1 == 1000 && p0 != null) {
            suggestionAdapter.setData(p0)
            if(searchMode == 0) binding.cardGlobalSuggestions.visibility = View.VISIBLE
            else findViewById<View>(R.id.cardSuggestions).visibility = View.VISIBLE
        }
    }

    private fun createWatcher(cb: (String) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { cb(s.toString()) }
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
    }

    private fun clearAllInputs() {
        findViewById<EditText>(R.id.etStart).text.clear()
        findViewById<EditText>(R.id.etEnd).text.clear()
        findViewById<EditText>(R.id.etPrice).text.clear()
        findViewById<AutoCompleteTextView>(R.id.autoCompleteTransport).setText(transportOptions[0], false)
        startLatLon = null; endLatLon = null
        findViewById<View>(R.id.cardSuggestions).visibility = View.GONE
        findViewById<View>(R.id.layoutDetails).visibility = View.VISIBLE
    }

    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { super.onPause(); binding.mapView.onPause() }
    override fun onDestroy() { super.onDestroy(); binding.mapView.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.mapView.onSaveInstanceState(outState) }
}