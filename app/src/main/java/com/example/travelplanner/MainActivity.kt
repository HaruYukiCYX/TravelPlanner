package com.harukayuki.travelplanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    private var searchMode: Int = 0 // 0:顶部, 1:起点, 2:终点
    private var startLatLon: LatLonPoint? = null
    private var endLatLon: LatLonPoint? = null
    private var activeTripId: Int = -1
    private var myCurrentLatLon: LatLonPoint? = null

    private val transportOptions = arrayOf("汽车", "飞机", "高铁", "火车", "自行车", "地铁")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)
        com.amap.api.maps.MapsInitializer.updatePrivacyShow(this, true, true)
        com.amap.api.maps.MapsInitializer.updatePrivacyAgree(this, true)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.onCreate(savedInstanceState)
        aMap = binding.mapView.map

        checkPermissions()
        initMapConfig()

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "travel_final_v21")
            .fallbackToDestructiveMigration().build()

        setupTransportMenu()
        setupRecyclerViews()
        initInteractions()
        loadAllData()
    }

    private fun initMapConfig() {
        aMap.uiSettings.isZoomControlsEnabled = false
        val myLocationStyle = MyLocationStyle()
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
        myLocationStyle.strokeColor(Color.TRANSPARENT)
        aMap.myLocationStyle = myLocationStyle
        aMap.isMyLocationEnabled = true
        aMap.setOnMyLocationChangeListener { location ->
            myCurrentLatLon = LatLonPoint(location.latitude, location.longitude)
        }
    }

    private fun checkPermissions() {
        val p = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, p[0]) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, p, 100)
        }
    }

    private fun setupRecyclerViews() {
        tripAdapter = TripAdapter(
            onHeaderClick = { trip -> trip.isExpanded = !trip.isExpanded; activeTripId = trip.id; refreshSidebarAndMap() },
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
        binding.rvItineraries.layoutManager = LinearLayoutManager(this); binding.rvItineraries.adapter = tripAdapter

        suggestionAdapter = SuggestionAdapter { handleSuggestionClick(it) }
        binding.rvGlobalSuggestions.layoutManager = LinearLayoutManager(this); binding.rvGlobalSuggestions.adapter = suggestionAdapter

        findViewById<RecyclerView>(R.id.rvSuggestions).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = suggestionAdapter
        }
    }

    /**
     * 核心修复：点击建议条目后的处理逻辑
     */
    private fun handleSuggestionClick(tip: Tip) {
        if (tip.point == null) return

        // 1. 物理隐藏所有建议视图层
        binding.cardGlobalSuggestions.visibility = View.GONE
        findViewById<View>(R.id.cardSuggestions).visibility = View.GONE
        findViewById<View>(R.id.layoutDetails).visibility = View.VISIBLE

        // 2. 清除所有输入框的焦点，防止 TextWatcher 因为 setText 再次弹窗
        binding.etGlobalSearch.clearFocus()
        val etStart = findViewById<EditText>(R.id.etStart)
        val etEnd = findViewById<EditText>(R.id.etEnd)
        etStart.clearFocus()
        etEnd.clearFocus()

        when (searchMode) {
            0 -> {
                aMap.clear()
                val latLng = LatLng(tip.point.latitude, tip.point.longitude)
                aMap.addMarker(MarkerOptions().position(latLng).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)))
                aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                binding.tvResultName.text = tip.name
                binding.tvResultAddress.text = tip.address ?: "暂无详细地址"
                binding.cardSearchResult.visibility = View.VISIBLE
            }
            1 -> {
                etStart.setText(tip.name)
                startLatLon = tip.point
            }
            2 -> {
                etEnd.setText(tip.name)
                endLatLon = tip.point
            }
        }
    }

    private fun initInteractions() {
        val sheet = BottomSheetBehavior.from(findViewById(R.id.bottomSheet))

        // 顶部搜索：增加 focus 判断，解决自动弹出的问题
        binding.etGlobalSearch.addTextChangedListener(createWatcher {
            if (it.isNotEmpty() && binding.etGlobalSearch.hasFocus()) {
                searchMode = 0
                requestTips(it)
                binding.btnSearchClear.visibility = View.VISIBLE
                binding.cardGlobalSuggestions.visibility = View.VISIBLE
            } else {
                binding.cardGlobalSuggestions.visibility = View.GONE
            }
        })

        binding.btnLocateMe.setOnClickListener {
            aMap.myLocation?.let {
                aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 16f))
                binding.cardMyLocationPrompt.visibility = View.VISIBLE
            }
        }

        binding.btnStartPlanFromHere.setOnClickListener {
            if (myCurrentLatLon != null) {
                activeTripId = -1; clearAllInputs()
                findViewById<EditText>(R.id.etStart).setText("我的位置")
                startLatLon = myCurrentLatLon; binding.cardMyLocationPrompt.visibility = View.GONE
                sheet.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        // 修复 Unresolved Reference btnDone
        findViewById<Button>(R.id.btnDone).setOnClickListener {
            val trans = findViewById<AutoCompleteTextView>(R.id.autoCompleteTransport).text.toString()
            val price = findViewById<EditText>(R.id.etPrice).text.toString()
            val sName = findViewById<EditText>(R.id.etStart).text.toString()
            val eName = findViewById<EditText>(R.id.etEnd).text.toString()
            if (startLatLon != null && endLatLon != null) saveNewSegment(sName, eName, trans, price)
            else Toast.makeText(this, "请选择地点", Toast.LENGTH_SHORT).show()
        }

        binding.btnSearchClear.setOnClickListener { binding.etGlobalSearch.text.clear() }
        binding.btnMenu.setOnClickListener { binding.drawerLayout.open() }
        binding.btnCloseLocCard.setOnClickListener { binding.cardMyLocationPrompt.visibility = View.GONE }
        binding.btnCloseResultCard.setOnClickListener { binding.cardSearchResult.visibility = View.GONE }
        binding.btnAdd.setOnClickListener { activeTripId = -1; clearAllInputs(); sheet.state = BottomSheetBehavior.STATE_EXPANDED }

        // 规划面板搜索：增加 hasFocus() 判断，彻底杜绝关不掉的问题
        val etStart = findViewById<EditText>(R.id.etStart)
        val etEnd = findViewById<EditText>(R.id.etEnd)

        etStart.addTextChangedListener(createWatcher {
            if (it.isNotEmpty() && etStart.hasFocus()) {
                searchMode = 1; requestTips(it)
                findViewById<View>(R.id.cardSuggestions).visibility = View.VISIBLE
                findViewById<View>(R.id.layoutDetails).visibility = View.GONE
            }
        })
        etEnd.addTextChangedListener(createWatcher {
            if (it.isNotEmpty() && etEnd.hasFocus()) {
                searchMode = 2; requestTips(it)
                findViewById<View>(R.id.cardSuggestions).visibility = View.VISIBLE
                findViewById<View>(R.id.layoutDetails).visibility = View.GONE
            }
        })

        binding.btnStartPlanWithThis.setOnClickListener {
            activeTripId = -1; clearAllInputs()
            findViewById<EditText>(R.id.etEnd).setText(binding.tvResultName.text)
            // 设置当前地图中心点为终点坐标
            endLatLon = LatLonPoint(aMap.cameraPosition.target.latitude, aMap.cameraPosition.target.longitude)
            binding.cardSearchResult.visibility = View.GONE; sheet.state = BottomSheetBehavior.STATE_EXPANDED
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
                drawAnimatedLine(curvePoints)

                // --- 核心修复：更健壮的位图生成 ---
                val markerView = createCustomMarkerView(seg.transport, seg.price)
                val bitmap = loadBitmapFromView(markerView)

                if (bitmap != null) {
                    aMap.addMarker(MarkerOptions()
                        .position(curvePoints[curvePoints.size / 2])
                        .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                        .anchor(0.5f, 0.5f)) // 居中锚点
                }

                // 起终点大头针
                aMap.addMarker(MarkerOptions().position(pStart).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)).title(seg.startLoc))
                aMap.addMarker(MarkerOptions().position(pEnd).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).title(seg.endLoc))
                boundsBuilder.include(pStart).include(pEnd)
            }
            try { aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 250)) } catch (e: Exception) {}
        }
    }

    /**
     * 强力 View 转 Bitmap 函数：解决蓝色方块问题的黑科技
     */
    private fun loadBitmapFromView(view: View): Bitmap? {
        try {
            // 1. 强制设置 View 的大小
            view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val width = view.measuredWidth
            val height = view.measuredHeight

            // 2. 必须设置布局范围
            view.layout(0, 0, width, height)

            // 3. 创建带透明通道的位图
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 4. 将 View 绘制到画布上
            view.draw(canvas)
            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun createCustomMarkerView(transport: String, price: String): View {
        val view = LayoutInflater.from(this).inflate(R.layout.custom_marker_view, null)
        val icon = view.findViewById<ImageView>(R.id.ivMarkerIcon)
        val tv = view.findViewById<TextView>(R.id.tvMarkerPrice)

        tv.text = "¥$price"

        // 匹配图标
        val resId = when (transport.trim()) {
            "飞机" -> R.drawable.ic_plane
            "高铁" -> R.drawable.ic_railway
            "汽车" -> R.drawable.ic_car
            "自行车" -> R.drawable.ic_bike
            "火车" -> R.drawable.ic_train
            "地铁" -> R.drawable.ic_metro
            else -> android.R.drawable.ic_menu_directions
        }
        icon.setImageResource(resId)

        return view
    }

    private fun saveNewSegment(sName: String, eName: String, trans: String, price: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (activeTripId == -1) activeTripId = db.travelDao().insertTrip(Trip(tripName = "新旅程")).toInt()
            db.travelDao().insertSegment(Segment(tripId = activeTripId, startLoc = sName, endLoc = eName, startLat = startLatLon!!.latitude, startLng = startLatLon!!.longitude, endLat = endLatLon!!.latitude, endLng = endLatLon!!.longitude, transport = trans, price = price))
            loadAllData()
            withContext(Dispatchers.Main) { BottomSheetBehavior.from(findViewById(R.id.bottomSheet)).state = BottomSheetBehavior.STATE_COLLAPSED; clearAllInputs() }
        }
    }

    private fun loadAllData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val trips = db.travelDao().getAllTrips()
            val tempMap = mutableMapOf<Int, List<Segment>>()
            trips.forEach { nt -> nt.isExpanded = allTrips.find { it.id == nt.id }?.isExpanded ?: false; tempMap[nt.id] = db.travelDao().getSegmentsByTrip(nt.id) }
            withContext(Dispatchers.Main) {
                allTrips.clear(); allTrips.addAll(trips); segmentsData.clear(); segmentsData.putAll(tempMap); refreshSidebarAndMap()
            }
        }
    }

    private fun toggleFavorite(trip: Trip) {
        trip.isFavorite = !trip.isFavorite
        lifecycleScope.launch(Dispatchers.IO) { db.travelDao().updateTrip(trip); loadAllData() }
    }

    private fun drawAnimatedLine(points: List<LatLng>) {
        val polyline = aMap.addPolyline(PolylineOptions().width(18f).color(Color.parseColor("#2196F3")).lineJoinType(PolylineOptions.LineJoinType.LineJoinRound))
        lifecycleScope.launch { val current = mutableListOf<LatLng>(); for (p in points) { current.add(p); polyline.points = current; delay(12) } }
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

    private fun showEditTripNameDialog(trip: Trip) {
        val et = EditText(this).apply { setText(trip.tripName) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("修改名字").setView(et).setPositiveButton("确定") { _, _ -> val n = et.text.toString().trim(); if (n.isNotEmpty()) { trip.tripName = n; lifecycleScope.launch(Dispatchers.IO) { db.travelDao().updateTrip(trip); loadAllData() } } }.show()
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
    private fun createWatcher(cb: (String) -> Unit) = object : TextWatcher { override fun afterTextChanged(s: Editable?) { cb(s.toString()) }; override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}; override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {} }
    private fun clearAllInputs() { findViewById<EditText>(R.id.etStart).text.clear(); findViewById<EditText>(R.id.etEnd).text.clear(); findViewById<EditText>(R.id.etPrice).text.clear(); startLatLon = null; endLatLon = null; findViewById<View>(R.id.cardSuggestions).visibility = View.GONE; findViewById<View>(R.id.layoutDetails).visibility = View.VISIBLE }
    private fun setupTransportMenu() { findViewById<AutoCompleteTextView>(R.id.autoCompleteTransport).apply { setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, transportOptions)); setText(transportOptions[0], false) } }

    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { super.onPause(); binding.mapView.onPause() }
    override fun onDestroy() { super.onDestroy(); binding.mapView.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.mapView.onSaveInstanceState(outState) }
}