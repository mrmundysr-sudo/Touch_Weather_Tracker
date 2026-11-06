# Verified patterns

- Resolve upstream service layer ids by name, not number (TIGERweb, NHC ArcGIS).
- Wrap every upstream call in a cache-with-timestamp (`Loaded<T>`): failed refresh returns the last copy marked stale.
- Keep report/landfall logic pure (`domain/`) and unit-test it with archived real data formats.
