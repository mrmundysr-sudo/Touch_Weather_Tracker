# Verified lessons

- TIGERweb layer ids change between releases – resolve by layer name at runtime.
- The USACE Blue Roof site and nowCOAST legacy `arcgis`/`layerinfo` endpoints block automated clients; use OpenFEMA mission assignments and the nowCOAST GeoServer WMS (`/geoserver/satellite/wms`).
- ECMWF is not in NHC's public `aid_public` a-decks (only AVNO/GFS, CMC, UKX, NGX, HWRF, etc.).
- The sandbox needs each data host allow-listed and Maven Central rate-limits parallel downloads (`--max-workers=2` and retries).
- Widget preview layouts (`android:previewLayout`) are inflated as RemoteViews: a plain `<View>`/`Space` makes the launcher show "Problem loading widget". Use FrameLayout/LinearLayout/TextView/ImageView only (enforced by `WidgetPreviewLayoutTest`).
