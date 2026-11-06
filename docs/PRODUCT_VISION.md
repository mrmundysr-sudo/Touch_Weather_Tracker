# Touch Weather Tracker — Claude Build Brief

## Assignment

Build Touch Weather Tracker for iOS and Android as a polished, complete, store-ready first pass. The product should feel cohesive and professionally designed across platforms, with careful visual design, reliable data handling, accessibility, and fully working end-to-end features.

The owner will install and test the builds, then provide feedback. Do not submit or publish to either app store. Before any store submission, complete a full app review with the owner and address the review findings. Use your full judgment and autonomy to make product, design, and engineering decisions that improve the app while preserving the agreed product and feature list in this brief. Do not pause for routine approvals. Deliver functioning features, not demos, mock screens, sample-data modes, or placeholder flows. Do not represent a nonfunctional feature as finished.

## Product vision

A U.S.-wide everyday weather and severe-weather companion. It should help people understand what is happening at home and at places important to them, prepare for hurricanes and other hazards, find nearby recovery resources, and act on official information without having to interpret a pile of raw feeds or links.

The signature feature is a concise, location-focused storm report that combines official forecasts with recent aircraft, buoy, coastal-gauge, and warning information when each source is available and suitable. The app explains what is forecast, what has been observed, where, and when. It omits stale, missing, or unsuitable inputs rather than filling gaps with guesses.

## Platforms, audience, and launch scope

- iOS and Android, nationwide U.S. launch.
- U.S. nationwide coverage is the initial target. Do not imply international coverage unless implemented and supported by reliable sources.
- Support one Home location and multiple saved Places of Interest, for family, friends, work, or travel.
- Users may set a location from device location, street address, ZIP code, or map/location search. Let users edit or remove saved places and choose a default place.
- Allow users to favorite states and counties as broader areas of interest, particularly for following family and friends.

## Core experience and navigation

Create a clear, professional home screen that works in calm weather and becomes storm-focused when hazards are active. Suggested main areas: Today, Map, Alerts, Storms, and Resources, plus saved places and settings. Claude may improve this architecture if the result is simpler and more coherent.

Every screen should distinguish current observations, forecasts, official warnings, model guidance, and third-party summaries. Show source and issue/update time where it changes a decision. Use plain language and accessible colors, labels, contrast, type scaling, and screen-reader semantics. Do not depend on color alone for alert severity.

## Everyday weather

- Current conditions, feels-like temperature, wind/gusts, precipitation, humidity, visibility, and other available useful fields.
- Hourly and daily forecasts, high/low, precipitation chance/amount when supplied, sunrise/sunset, and severe-weather outlook.
- Usable for any U.S. location, with a reliable location search and saved-place switching.
- Offline or source-outage state should preserve the last available forecast with its timestamp and make clear it is not current.
- Use NWS/NOAA free official data where available. No paid weather API or service that creates a recurring cost to the owner.

## Radar, maps, satellite, and hurricane tracking

- Provide a polished map with optional layers for radar, satellite, warnings, tracks, and observed conditions.
- Investigate and use suitable free U.S. government radar sources: NWS RIDGE2/OGC services as the primary candidate, with the previously discussed NWS radar display and MRMS data as fallback options where technically and legally suitable. Use a dependable map base that does not require paid hosting or paid tiles; show required attribution.
- Add NOAA GOES satellite imagery suited to hurricane monitoring, including useful visible, infrared, or water-vapor views when available. Let users toggle hurricane tracks on/off over satellite imagery.
- Show the NHC official forecast track and cone as the authoritative hurricane forecast. Add a user-controlled comparison toggle for GFS and ECMWF model tracks, visually and textually labeled as model guidance, separate from the NHC forecast. Do not imply consensus or certainty where models differ.
- Include storm position, motion, intensity, wind extent/probability where supported, advisory text/key messages, and relevant NHC/NWS hazard products.
- Explain that the NHC cone describes forecast uncertainty in the center track, not the full storm footprint or all impact areas.

## Signature feature: plain-language storm report

Generate a concise report for Home or a selected saved place. It should answer: What is happening? What is forecast here? What has nearby equipment actually measured? What warnings or actions apply?

Use, when viable:

1. NHC official forecast/advisory for storm position, movement, intensity, and forecast.
2. NWS/NHC local hazard products, including wind, flood, storm-surge watches/warnings, and local instructions.
3. Reconnaissance data from official NHC products for aircraft center fixes, pressure, flight-level or surface-wind observations, and dropsonde context. Use current official operational products for live context; use historical NHC recon archive/AOML material for replay or additional research only when product terms and timing make it suitable. Do not depend on a third-party persistent live display such as Tropical Tidbits without permission.
4. Nearby NOAA NDBC buoys for wind, gust, pressure, significant wave height/period, and water temperature when reported.
5. NOAA CO-OPS water-level gauges for observed water levels and departures from prediction when available.
6. Official NHC storm-surge forecasts/maps and local NWS warnings for forecast surge/flood risk. Do not calculate surge from buoy readings or label wave height as surge.

Report rules:

- Clearly label observations versus forecasts and include station/source and timestamp.
- Use nearby station distance and location; do not represent one station as an entire coast.
- Treat absent readings as unavailable, not zero or safe. Omit feeds that are stale, low quality, incompatible with commercial reuse, or otherwise not viable.
- Do not invent a numeric risk, landfall certainty, or interpretation unsupported by an official product.
- Keep the main report short and understandable. An optional details panel can disclose sources, definitions, station distances, and freshness without cluttering the main screen.
- When no tropical system threatens the place, show a useful everyday-weather summary instead of an empty storm panel.

## Alerts and notifications — chosen defaults

Use NWS alerts as the core U.S. official hazard source. Resolve point locations to the appropriate NWS zones/counties and preserve each alert's official severity, area, description, instructions, issue time, and expiration/update state.

Default behavior:

- Enable app notifications for new NWS warnings and urgent life-safety alerts affecting the Home location after the user grants notification permission.
- Do not enable routine watches, advisories, forecast changes, or every saved location by default. Let users select alert types per saved place.
- Let users opt in to watches and lower-severity notices for any saved place. Provide per-place controls for Home, family/friends, and favorites.
- De-duplicate overlapping NWS alerts and notify on new alerts or material updates/cancellations, not on each poll. Do not silently suppress a new severe alert because of quiet hours.
- Make alert settings understandable: location, hazard/severity, alert type, sound/vibration behavior where supported, and clear-off controls.
- Encourage users to keep Wireless Emergency Alerts enabled; app notifications supplement government emergency alerts and do not replace them.

## Temporary hurricane event mode / background prompt

Offer temporary event mode to all U.S. users when the official NHC forecast indicates a hurricane is expected to make U.S. landfall within 72 hours. Use the latest official forecast and re-evaluate as it changes. Do not trigger from a single GFS/ECMWF run or an unofficial source.

- Explain the benefit in plain language: event mode can help the app refresh relevant saved-place storm information and urgent app alerts during the event. Never promise instant or guaranteed delivery.
- The prompt offers clear actions such as Enable for this event, Not now, and Details. Respect a decline; do not repeatedly nag during the same storm. Allow the user to change the choice in settings.
- Request only platform permissions needed for the chosen behavior and explain them in context. iOS and Android control background execution and refresh frequency; design for those limits and do not claim the app can run continuously.
- If the user enabled event mode, prompt after the hurricane is no longer expected to affect U.S. saved places and relevant warnings have expired or been cancelled. Offer Return to normal and Keep event mode. Do not automatically remove user-selected permanent preferences.
- Event mode should use saved locations and official hazard products. Do not require continuous GPS tracking to monitor saved locations.
- If reliable notifications require a backend, paid service, or ongoing hosting, do not quietly add one. First use available free/no-cost options and document the practical limits clearly.

## Alerts and warnings screen

Build an at-a-glance feed that clearly shows affected state, county/zone, hazard level, concise warning description, source, issue/update time, and expiration. Tapping opens the full official detail and relevant map. Provide filters for Home, saved places, favorited counties/states, and all active alerts. Include a clear “no active alerts found” state that does not imply there can never be a warning.

## Power outage information

Power outage information is utility-first:

- Identify the likely local electric utility for each location using reliable service-area data. Allow manual correction because territory boundaries can be approximate.
- Provide direct, tappable utility outage-map/status, report-outage, phone, and official website actions. Keep this in the app's location and storm-resource context, not as a pile of generic links.
- Add an optional nationwide county-level outage overview using the MassOutage API if current terms and implementation remain suitable. Attribute “MassOutage (massoutage.com)” with a link as required by its CC BY 4.0 terms. Label it unofficial and supplemental, show source/update time, use the API's freshness status, and distinguish missing coverage from zero outages.
- Refresh at most every 11 minutes; cache responses and obey rate limits. Keep utility links as the primary source for reporting and local restoration details.
- Do not scrape or repackage utility feeds that prohibit resale or do not permit commercial reuse. ERCOT is a Texas-only grid-conditions resource, not a national outage source; include it only in relevant Texas context if useful.

## Integrated disaster resource center

This must be a useful in-app resource experience, not merely a link list. Center it on the selected address, ZIP code, current location, or saved place, with distance, status, contact/action steps, source, and last-verified/updated time where available. Let Claude choose the clearest presentation, but users should be able to understand what is available, who may qualify, what to do next, and how to confirm availability.

Include all previously discussed resource categories:

- FEMA disaster declarations and assistance: explain available programs, eligibility/rules, deadlines, how to apply or claim, and the official next step. Use OpenFEMA and current official FEMA pages; explain that eligibility and program availability vary by declaration and location.
- FEMA/state/local shelters: nearby open/current shelter information where available, phone/contact, operating status, distance, and last update. Provide pet accommodation states as: allowed (colored dog/cat icon), not allowed (grey icon crossed by red circle/line), or unknown/unreported (greyed-out icon without the red prohibition mark). Never infer pet acceptance from missing data.
- Food, water, charging, distribution, and recovery-resource sites from official government and recognized relief sources where feeds/listings are viable. Show whether a site is open/active and the source's update time; make uncertainty visible and offer a call/confirmation action.
- USACE Operation Blue Roof: determine whether an active mission exists for the location, show affected counties/eligibility, explain the official application process, and surface the official contact/phone/site. If no active mission exists, say so and provide the official program context rather than implying a crew will respond.
- Other official FEMA/local recovery resources already researched in this conversation, including disaster resource centers and aid pathways, integrated by location and event.

Never present an unverified listing as currently open. If a listing cannot be verified as current, explain that clearly and offer the best official local contact or action.

## Widgets

Build the same three agreed widget concepts on each platform, following the native widget conventions:

1. Everyday Weather: current conditions, high/low, and next few hours.
2. Saved Place: weather or active alerts for a user-selected Home/family/other place.
3. Storm Watch: active hurricane status, distance/direction, and latest official advisory.

Show data/advisory timestamps. Platform operating systems control refresh timing, so never present a widget as instant. Tapping opens the corresponding in-app screen. Include sizes/layouts that are practical and legible.

## Monetization

- Free app supported by ads.
- Four completed ad views unlock the full premium app for 24 hours.
- Offer a paid ad-free option and a subscription covering the owner's full app portfolio.
- All premium routes unlock the exact same feature set; do not create a reduced-feature premium tier.
- No ads may obscure active warnings, emergency directions, or essential resource actions. Preserve usability during an event and make ad timing respectful.
- Fully integrate ads, the 24-hour ad unlock, the paid ad-free option, and the all-app subscription using platform-compliant purchase flows. Provide working purchase restoration and clear entitlement state. Implement the real purchase and entitlement flows; do not substitute mock purchases or demo unlocks.

## Design and quality bar

- Create a distinctive, premium weather identity for Touch Weather Tracker. It should feel calm and clear in everyday use and shift to urgent, focused hierarchy during severe weather.
- Use strong typography, deliberate color, map legibility, polished loading/empty/error states, smooth but restrained transitions, and consistent design across iOS and Android.
- Include accessibility: dynamic text, screen-reader labels, contrast, non-color severity labels, reduced-motion support, and large tap targets.
- Support dark/light appearance if appropriate, localization-ready strings, correct time zones, units, and U.S. address/state/county conventions.
- Make the app robust to slow networks, API outages, stale feeds, incomplete data, denied permissions, and offline use. Preserve cached information with honest timestamps.
- Provide onboarding that gets a user to useful weather quickly and lets them add Home, saved places, notification preferences, and map/resource permissions progressively.
- Do not over-prompt for permissions. Explain background/notification choices in context.
- Keep data-source adapters modular so sources can be replaced without redesigning product logic.

## Cost, hosting, data use, and attribution constraints

- The target is no recurring developer cost and no owner-managed hosting. Prefer free official government services and locally cached data.
- Do not add paid map tiles, commercial weather APIs, paid geocoding, or required server hosting without flagging it as a blocker and proposing a no-cost fallback.
- Respect source terms. “Publicly viewable” does not automatically mean commercial redistribution is allowed. Attribute sources; do not imply government endorsement. For MassOutage, retain its stated attribution/link and unofficial-data notice.
- Do not use private undocumented endpoints or scrape restricted utility maps. For any source without clear commercial reuse rights, link to the official source or omit the integrated data.
- Be candid that reliable server-originated push notifications and app-wide event alerts may require hosted infrastructure. Implement only what can be supported within the no-cost/no-hosting constraint, and document limitations rather than promising delivery.

## Delivery expectations

Deliver a complete, installable iOS and Android first pass with coherent navigation and every agreed core feature fully implemented. Do not leave placeholder screens, mocked functionality, sample-data modes, or unfinished feature flows. If a particular observation is absent for a location or time, omit that observation from the report as specified above; this is normal data handling, not a demo mode.

Include:

- A concise build README and source inventory documenting the integrated services, attribution, refresh behavior, and architecture.
- A source inventory documenting endpoint/product, terms/attribution, expected refresh, coverage, stale behavior, and fallback for each integrated data source.
- A short feature checklist marking implemented, implemented with limits, or blocked, with a reason and next action.
- Final screenshots or a short review guide so the owner can inspect the completed working flows before testing.

Finish with a full review checklist for the owner. Store submission and release happen only after the owner has reviewed the complete app and explicitly approved moving forward.
