package com.harukayuki.travelplanner

import androidx.room.*

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) var id: Int = 0,
    var tripName: String = "",
    var isFavorite: Boolean = false // 新增：是否收藏
) {
    @Ignore var isExpanded: Boolean = false
}

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(
        entity = Trip::class,
        parentColumns = ["id"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["tripId"])]
)
data class Segment(
    @PrimaryKey(autoGenerate = true) var id: Int = 0,
    var tripId: Int = 0,
    var startLoc: String = "",
    var endLoc: String = "",
    var startLat: Double = 0.0,
    var startLng: Double = 0.0,
    var endLat: Double = 0.0,
    var endLng: Double = 0.0,
    var transport: String = "",
    var price: String = ""
)

@Dao
interface TravelDao {
    @Insert suspend fun insertTrip(trip: Trip): Long
    @Query("SELECT * FROM trips ORDER BY isFavorite DESC, id DESC") suspend fun getAllTrips(): List<Trip>
    @Update suspend fun updateTrip(trip: Trip)
    @Delete suspend fun deleteTrip(trip: Trip)

    @Insert suspend fun insertSegment(segment: Segment)
    @Query("SELECT * FROM segments WHERE tripId = :tripId") suspend fun getSegmentsByTrip(tripId: Int): List<Segment>
    @Query("DELETE FROM segments WHERE tripId = :tripId") suspend fun deleteSegmentsByTrip(tripId: Int)
}

@Database(entities = [Trip::class, Segment::class], version = 10) // 版本升到 10
abstract class AppDatabase : RoomDatabase() {
    abstract fun travelDao(): TravelDao
}