package com.example.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * OpenStreetMap (OSM) interactive map view for El-Batal Express Captain.
 * 100% Free & Open Source - NO Google Maps SDK / NO Google Maps API key required.
 *
 * Displays:
 * 🏍️ Captain live position
 * 📍 Trip assigned Restaurant position
 */
@Composable
fun TripOsmMapView(
    captainLat: Double?,
    captainLng: Double?,
    restaurantLat: Double?,
    restaurantLng: Double?,
    restaurantName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Initialize osmdroid user agent configuration
    remember {
        Configuration.getInstance().userAgentValue = context.packageName
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid_pref", Context.MODE_PRIVATE))
        true
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .border(1.5.dp, HeroYellow.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Android OSM Native Map View
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    map.overlays.clear()

                    val captainGeo = if (captainLat != null && captainLng != null && captainLat != 0.0 && captainLng != 0.0) {
                        GeoPoint(captainLat, captainLng)
                    } else null

                    val restaurantGeo = if (restaurantLat != null && restaurantLng != null && restaurantLat != 0.0 && restaurantLng != 0.0) {
                        GeoPoint(restaurantLat, restaurantLng)
                    } else null

                    // 1. Restaurant Marker
                    restaurantGeo?.let { rGeo ->
                        val rMarker = Marker(map).apply {
                            position = rGeo
                            title = "المتجر: $restaurantName"
                            snippet = "وجهة استلام الوجبات"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        map.overlays.add(rMarker)
                    }

                    // 2. Captain Live Marker
                    captainGeo?.let { cGeo ->
                        val cMarker = Marker(map).apply {
                            position = cGeo
                            title = "موقع الكابتن المباشر"
                            snippet = "يتم التحديث عبر GPS"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                        map.overlays.add(cMarker)
                    }

                    // Center logic
                    when {
                        captainGeo != null && restaurantGeo != null -> {
                            val midLat = (captainGeo.latitude + restaurantGeo.latitude) / 2.0
                            val midLng = (captainGeo.longitude + restaurantGeo.longitude) / 2.0
                            map.controller.setCenter(GeoPoint(midLat, midLng))
                        }
                        captainGeo != null -> {
                            map.controller.setCenter(captainGeo)
                        }
                        restaurantGeo != null -> {
                            map.controller.setCenter(restaurantGeo)
                        }
                        else -> {
                            // Default to Cairo / Egypt central if no GPS fix yet
                            map.controller.setCenter(GeoPoint(30.0444, 31.2357))
                        }
                    }

                    map.invalidate()
                }
            )

            // Top overlay banner: OSM status indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = HeroYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "خريطة OpenStreetMap الحرة",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(StatusOnlineGreen)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "GPS Live",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = StatusOnlineGreen
                    )
                }
            }

            // Floating Controls: Center Captain, Center Restaurant, Zoom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Focus Captain
                IconButton(
                    onClick = {
                        if (captainLat != null && captainLng != null && captainLat != 0.0 && captainLng != 0.0) {
                            mapView.controller.animateTo(GeoPoint(captainLat, captainLng))
                            mapView.controller.setZoom(16.5)
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(ExpressRed),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Default.ElectricScooter,
                        contentDescription = "التركيز على الكابتن",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Focus Restaurant
                IconButton(
                    onClick = {
                        if (restaurantLat != null && restaurantLng != null && restaurantLat != 0.0 && restaurantLng != 0.0) {
                            mapView.controller.animateTo(GeoPoint(restaurantLat, restaurantLng))
                            mapView.controller.setZoom(16.5)
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(HeroYellow),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.Black)
                ) {
                    Icon(
                        imageVector = Icons.Default.Storefront,
                        contentDescription = "التركيز على المتجر",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Zoom in (+)
                IconButton(
                    onClick = { mapView.controller.zoomIn() },
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "تكبير الخريطة", modifier = Modifier.size(18.dp))
                }

                // Zoom out (-)
                IconButton(
                    onClick = { mapView.controller.zoomOut() },
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "تصغير الخريطة", modifier = Modifier.size(18.dp))
                }
            }

            // Legend at bottom left
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ElectricScooter, contentDescription = null, tint = ExpressRed, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("الكابتن", style = MaterialTheme.typography.labelSmall, color = Color.White, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = HeroYellow, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("المتجر", style = MaterialTheme.typography.labelSmall, color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}
