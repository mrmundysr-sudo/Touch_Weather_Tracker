package com.mundymorning.touchweathertracker.ui

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.util.Locale

private const val ORIGIN = 20037508.342789244

/** Web-Mercator bounding box of a slippy-map tile: [minx, miny, maxx, maxy]. */
fun tileBbox(z: Int, x: Int, y: Int): DoubleArray {
    val size = 2 * ORIGIN / (1 shl z)
    val minx = -ORIGIN + x * size
    val maxy = ORIGIN - y * size
    return doubleArrayOf(minx, maxy - size, minx + size, maxy)
}

/** NOAA/NWS RIDGE2 / MRMS base reflectivity, drawn through the ArcGIS `export` endpoint as 256px tiles. */
class RadarTileSource(name: String) : OnlineTileSourceBase(name, 0, 11, 256, ".png", arrayOf(BASE)) {
    companion object { const val BASE = "https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity/MapServer/export" }
    override fun getTileURLString(i: Long): String {
        val b = tileBbox(MapTileIndex.getZoom(i), MapTileIndex.getX(i), MapTileIndex.getY(i))
        return String.format(Locale.US, "%s?bbox=%.3f,%.3f,%.3f,%.3f&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&f=image", BASE, b[0], b[1], b[2], b[3])
    }
}

/** WMS GetMap tiles (nowCOAST GOES imagery). */
class WmsTileSource(name: String, private val wms: String, private val layer: String) : OnlineTileSourceBase(name, 0, 9, 256, ".png", arrayOf(wms)) {
    override fun getTileURLString(i: Long): String {
        val b = tileBbox(MapTileIndex.getZoom(i), MapTileIndex.getX(i), MapTileIndex.getY(i))
        return String.format(Locale.US, "%s?service=WMS&version=1.3.0&request=GetMap&layers=%s&styles=&crs=EPSG:3857&bbox=%.3f,%.3f,%.3f,%.3f&width=256&height=256&format=image/png&transparent=true", wms, layer, b[0], b[1], b[2], b[3])
    }
}

/** USGS The National Map basemaps (free, public domain). ArcGIS tile order is z/y/x. */
class UsgsTileSource(name: String, service: String) : OnlineTileSourceBase(name, 0, 15, 256, ".jpg", arrayOf("https://basemap.nationalmap.gov/arcgis/rest/services/$service/MapServer/tile/")) {
    override fun getTileURLString(i: Long): String = baseUrl + MapTileIndex.getZoom(i) + "/" + MapTileIndex.getY(i) + "/" + MapTileIndex.getX(i)
}
