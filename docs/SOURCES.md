# Source inventory

Every integrated source: endpoint/product, terms/attribution, refresh, coverage, stale behavior, fallback. All are free, keyless and called directly from the device. User-Agent: `TouchWeatherTracker/0.1 (Android; github.com/mrmundysr-sudo/Touch_Weather_Tracker)`.

| Source | Endpoint / product | Terms & attribution | Refresh | Coverage | When stale / fails | Fallback |
|---|---|---|---|---|---|---|
| NWS API | `api.weather.gov` `/points`, `/gridpoints/*/forecast[/hourly]`, gridpoint data (feels-like, gust, QPF), `/stations/*/observations/latest`, `/alerts/active`, `/products/types/HWO` | U.S. Gov work; UA required; no endorsement implied | Weather 10-min cache; alerts 90 s; background 30 min (15 min event mode) | U.S. + territories NWS covers | Last forecast kept with "not current" banner; alerts show "could not refresh" | Next nearest station; cached copy |
| NHC active storms | `nhc.noaa.gov/CurrentStorms.json` | U.S. Gov | 4-min cache | Atlantic/E. & C. Pacific | Cached list labeled; hidden if never loaded | – |
| NHC ATCF | `ftp.nhc.noaa.gov/atcf/aid_public/a<id>.dat.gz` (OFCL official forecast, AVNO = GFS), `atcf/btk/b<id>.dat` | U.S. Gov; model tracks labeled as guidance | per fetch | active storms | Track omitted | – |
| NHC graphics | `mapservices.weather.noaa.gov/tropical/.../NHC_tropical_weather/MapServer` (cone, track, watch-warning, past track) layers resolved by name | U.S. Gov | per fetch | active storms | ATCF official points are drawn instead | – |
| NHC advisory text | `nhc.noaa.gov/text/*.shtml` | U.S. Gov | per fetch | active storms | Section omitted | Link to product |
| NHC recon | `nhc.noaa.gov/archive/recon/<yr>/AHONT1..` (HDOB), `…/REPNT3` (dropsondes), `tgftp.nws.noaa.gov/data/raw/ur/urnt12.knhc..txt` (VDM) | U.S. Gov products; no third-party live display | last 12 h, within 450 km of center | active storms | Omitted from report, noted in details | – |
| NDBC | `ndbc.noaa.gov/data/latest_obs/latest_obs.txt` | NOAA public | per report; reports >3 h old ignored | marine/coastal | Omitted (missing ≠ zero) | – |
| CO-OPS | `api.tidesandcurrents.noaa.gov` (stations, water_level, predictions) | NOAA public; water level never labeled surge | per report; >2 h old ignored | U.S. coasts | Omitted | – |
| Radar | `mapservices.weather.noaa.gov/eventdriven/.../radar_base_reflectivity/MapServer/export` (MRMS) | NOAA public | tiles rebuilt every 10 min; **service publishes no scan time** (UI says so) | CONUS, AK, HI, PR, Guam | Blank tiles | – |
| Satellite | `nowcoast.noaa.gov/geoserver/satellite/wms` (`goes_visible/longwave/water_vapor_imagery`) | NOAA public | latest time read from capabilities, 5-min cache | GOES East/West | Layer blank | – |
| Basemap | `basemap.nationalmap.gov` USGSTopo / USGSImageryTopo | USGS public domain; attribution shown | cached on device | U.S. | Cached tiles | – |
| Census | `geocoding.geo.census.gov` (addresses), `tigerweb.geo.census.gov` (ZIP/ZCTA, places, counties, states) | U.S. Gov; search text sent to Census | per search | U.S. | Search error; saved places unaffected | – |
| FEMA | `fema.gov/api/open/v2` (declarations, mission assignments), `gis.fema.gov` NSS shelters (FEMA_NSS), DRC | FEMA public; pet status shown only if reported | per Resources open | U.S. | Section error with retry | 211 / DisasterAssistance.gov actions |
| Utility territories | HIFLD Electric Retail Service Territories via ArcGIS mirror `services3.arcgis.com/OYP7N6mAJJCyH6hd/.../Electric_Retail_Service_Territories_HIFLD` | Public-domain HIFLD data, **third-party-hosted mirror** (risk: may disappear) | 11-min cache | U.S. | Manual utility entry | manual |
| MassOutage | `massoutage.com/api/v1/live/counties/{fips}`, `/live/summary` | CC BY 4.0 – "MassOutage (massoutage.com)" with link; unofficial/supplemental | ≥11 min between calls; keyless limit 5 req/5 min | County-level where feeds exist | Shows data age/feed status; "no coverage" ≠ zero | utility links |

Not integrated (and why): ECMWF tracks (see KNOWN_ISSUES), USACE Blue Roof web page (blocks automated access – OpenFEMA mission assignments are used instead), any state/local shelter or food/water site feed (no single official viable feed), Tropical Tidbits (not permitted for live reuse).
